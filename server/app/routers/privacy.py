"""Privacy: data export and targeted deletion (docs/API.md "Privacy")."""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Response
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from .. import models as m
from ..access import commit_and_delete_blobs
from ..auth import current_user
from ..cleanup import couple_of
from ..consent import companion_status, delete_voice_data
from ..deps import get_db, get_store
from ..providers import Providers, get_providers
from ..schemas import (
    appearance_version_out,
    consent_event_out,
    consents_out,
    couple_out,
    memory_out,
    message_out,
    occasion_out,
    persona_out,
    photo_out,
    suggestion_out,
    user_out,
    wardrobe_out,
)
from ..storage import BlobStore
from ..util import iso, utcnow

router = APIRouter(prefix="/v1/privacy")


def _companion_export(db: Session, c: m.Companion, user: m.User) -> dict[str, Any]:
    """Everything stored for one companion that the caller is allowed to see.

    As subject: everything (including a persona kept while personality consent is off).
    As viewer: the same visibility rules as the Companion object (nothing while the subject's
    likeness consent is missing or revoked; the persona only while personality consent is on), and
    appearance versions limited to approved ones plus the caller's own proposals."""
    is_subject = user.id == c.subject_id
    status = companion_status(db, c)
    hidden = not is_subject and status in ("awaiting_consent", "revoked")

    versions = db.scalars(
        select(m.AppearanceVersion).where(m.AppearanceVersion.companion_id == c.id).order_by(m.AppearanceVersion.seq)
    ).all()
    if not is_subject:
        versions = [v for v in versions if v.status == "approved" or v.authored_by == user.id]
    photos = db.scalars(select(m.Photo).where(m.Photo.companion_id == c.id).order_by(m.Photo.seq)).all()
    samples = db.scalars(select(m.VoiceSample).where(m.VoiceSample.companion_id == c.id)).all()
    subject = db.get(m.User, c.subject_id)
    viewer = db.get(m.User, c.viewer_id)

    if is_subject:
        persona = persona_out(c.persona)
    else:
        persona = None if hidden or not c.consent_personality else persona_out(c.persona)
    return {
        "id": c.id,
        "role": "subject" if is_subject else "viewer",
        "subject": user_out(subject),
        "viewer": user_out(viewer),
        "name": c.name or subject.display_name,
        "status": status,
        "consents": consents_out(c),
        "appearance_versions": [] if hidden else [appearance_version_out(v) for v in versions],
        "persona": persona,
        "pending_persona": None if hidden else persona_out(c.pending_persona),
        "voice": {"status": c.voice_status, "samples": len(samples)},
        "photos": [photo_out(p) for p in photos],  # metadata only, never bytes
        "created_at": iso(c.created_at),
        "updated_at": iso(c.updated_at),
    }


@router.get("/export")
def export(user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    couple = couple_of(db, user.id)
    partner = None
    companions: list[m.Companion] = []
    if couple is not None:
        partner = db.get(m.User, couple.user_b_id if couple.user_a_id == user.id else couple.user_a_id)
        companions = list(db.scalars(select(m.Companion).where(m.Companion.couple_id == couple.id)).all())
    own = next((c for c in companions if c.subject_id == user.id), None)
    talks_to = next((c for c in companions if c.viewer_id == user.id), None)
    companion_ids = [c.id for c in companions]

    ledger = db.scalars(
        select(m.ConsentEvent).where(m.ConsentEvent.companion_id.in_(companion_ids)).order_by(m.ConsentEvent.seq.desc())
    ).all() if companion_ids else []
    wardrobe = db.scalars(
        select(m.WardrobeItem).where(m.WardrobeItem.companion_id == own.id).order_by(m.WardrobeItem.seq)
    ).all() if own else []
    memories = db.scalars(
        select(m.Memory).where(m.Memory.couple_id == couple.id)
        .order_by(m.Memory.happened_on.is_(None), m.Memory.happened_on.desc(), m.Memory.seq.desc())
    ).all() if couple else []
    occasions = db.scalars(
        select(m.Occasion).where(m.Occasion.couple_id == couple.id).order_by(m.Occasion.date, m.Occasion.seq)
    ).all() if couple else []
    messages = db.scalars(
        select(m.ChatMessage).where(m.ChatMessage.companion_id == talks_to.id).order_by(m.ChatMessage.seq)
    ).all() if talks_to else []
    suggestions = db.scalars(
        select(m.MemorySuggestion).where(m.MemorySuggestion.companion_id == talks_to.id).order_by(m.MemorySuggestion.seq)
    ).all() if talks_to else []

    return {
        "exported_at": iso(utcnow()),
        "user": user_out(user),
        "couple": couple_out(db, couple) if couple else None,
        "partner": user_out(partner) if partner else None,
        "companions": [_companion_export(db, c, user) for c in (own, talks_to) if c is not None],
        "consent_ledger": [consent_event_out(e) for e in ledger],
        "wardrobe": [wardrobe_out(w) for w in wardrobe],
        "memories": [memory_out(x) for x in memories],
        "occasions": [occasion_out(o) for o in occasions],
        "chat_messages": [message_out(x) for x in messages],
        "memory_suggestions": [suggestion_out(s) for s in suggestions],
    }


@router.delete("/photos", status_code=204)
def delete_my_photos(
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
) -> Response:
    """Every companion photo where the caller is subject or uploader, plus photos on memories the
    caller created."""
    now = utcnow()
    blobs: list[str] = []
    subject_of = select(m.Companion.id).where(m.Companion.subject_id == user.id)
    photos = db.scalars(
        select(m.Photo).where(or_(m.Photo.companion_id.in_(subject_of), m.Photo.uploaded_by == user.id))
    ).all()
    for p in photos:
        blobs.append(p.blob_name)
        companion = db.get(m.Companion, p.companion_id)
        if companion is not None:
            companion.updated_at = now
        db.delete(p)
    for memory in db.scalars(select(m.Memory).where(m.Memory.created_by == user.id, m.Memory.photo_blob.is_not(None))).all():
        blobs.append(memory.photo_blob)
        memory.photo_blob = None
        memory.photo_content_type = None
        memory.updated_at = now
    commit_and_delete_blobs(db, store, blobs)
    return Response(status_code=204)


@router.delete("/voice", status_code=204)
def delete_my_voice(
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
    providers: Providers = Depends(get_providers),
) -> Response:
    """Voice samples and any cloned voice where the caller is subject (consent is left as is)."""
    blobs: list[str] = []
    for c in db.scalars(select(m.Companion).where(m.Companion.subject_id == user.id)).all():
        blobs += delete_voice_data(db, providers, c)
        c.updated_at = utcnow()
    commit_and_delete_blobs(db, store, blobs)
    return Response(status_code=204)


@router.delete("/memories", status_code=204)
def delete_my_memories(
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
) -> Response:
    """Memories the caller created, and their photos."""
    blobs: list[str] = []
    for memory in db.scalars(select(m.Memory).where(m.Memory.created_by == user.id)).all():
        if memory.photo_blob:
            blobs.append(memory.photo_blob)
        db.delete(memory)
    commit_and_delete_blobs(db, store, blobs)
    return Response(status_code=204)
