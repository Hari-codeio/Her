from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Body, Depends, File, Response, UploadFile
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .. import models as m
from ..access import (
    commit_and_delete_blobs,
    fail,
    load_companion,
    read_upload,
    require_likeness,
    require_subject,
    require_viewer,
)
from ..auth import current_user
from ..config import CONSENT_STATEMENT_VERSION
from ..consent import apply_consent_change, approved_appearance
from ..deps import get_db, get_store
from ..providers import Providers, get_providers
from ..providers.likeness import LikenessJob
from ..schemas import (
    AppearanceIn,
    CompanionPatch,
    ConsentIn,
    PersonaIn,
    RejectIn,
    SpeakIn,
    companion_out,
    consent_event_out,
    photo_out,
)
from ..storage import BlobStore, sniff_audio_type, sniff_image_type
from ..util import iso, utcnow

router = APIRouter(prefix="/v1/companions")

MAX_PHOTOS = 20
MAX_VOICE_SAMPLES = 30


def _out(db: Session, c: m.Companion, user: m.User) -> dict[str, Any]:
    return companion_out(db, c, user.id)


# ---------------------------------------------------------------- lookup

@router.get("/mine")
def mine(user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    c = db.scalar(select(m.Companion).where(m.Companion.viewer_id == user.id))
    if c is None:
        raise fail(404, "not_paired")
    return _out(db, c, user)


@router.get("/of-me")
def of_me(user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    c = db.scalar(select(m.Companion).where(m.Companion.subject_id == user.id))
    if c is None:
        raise fail(404, "not_paired")
    return _out(db, c, user)


@router.get("/{companion_id}")
def get_companion(companion_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    return _out(db, load_companion(db, companion_id, user), user)


@router.patch("/{companion_id}")
def patch_companion(
    companion_id: str, body: CompanionPatch, user: m.User = Depends(current_user), db: Session = Depends(get_db)
) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    require_subject(c, user)
    if "name" in body.model_fields_set:
        c.name = body.name
        c.updated_at = utcnow()
        db.commit()
    return _out(db, c, user)


# ---------------------------------------------------------------- consent

@router.put("/{companion_id}/consent")
def put_consent(
    companion_id: str,
    body: ConsentIn,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
    providers: Providers = Depends(get_providers),
) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    require_subject(c, user)
    if body.statement_version != CONSENT_STATEMENT_VERSION:
        raise fail(409, "stale_statement")
    changes = body.model_dump(include={"likeness", "voice", "personality", "ai_training"})
    blobs = apply_consent_change(db, providers, c, user.id, changes)
    commit_and_delete_blobs(db, store, blobs)
    return _out(db, c, user)


@router.get("/{companion_id}/consent-history")
def consent_history(companion_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    c = load_companion(db, companion_id, user)
    rows = db.scalars(
        select(m.ConsentEvent).where(m.ConsentEvent.companion_id == c.id).order_by(m.ConsentEvent.seq.desc())
    ).all()
    return [consent_event_out(e) for e in rows]


# ---------------------------------------------------------------- appearance

@router.put("/{companion_id}/appearance")
def put_appearance(
    companion_id: str, body: AppearanceIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)
) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    require_likeness(c)
    now = utcnow()
    if user.id == c.subject_id:
        db.add(m.AppearanceVersion(companion_id=c.id, data=body.model_dump(), status="approved",
                                   authored_by=user.id, created_at=now, decided_at=now))
    else:
        old = db.scalars(select(m.AppearanceVersion).where(
            m.AppearanceVersion.companion_id == c.id, m.AppearanceVersion.status == "pending")).all()
        for version in old:  # a new viewer proposal replaces any older pending one
            db.delete(version)
        db.add(m.AppearanceVersion(companion_id=c.id, data=body.model_dump(), status="pending",
                                   authored_by=user.id, created_at=now))
    c.updated_at = now
    db.commit()
    return _out(db, c, user)


def _pending_version(db: Session, c: m.Companion, version_id: str) -> m.AppearanceVersion:
    v = db.scalar(select(m.AppearanceVersion).where(
        m.AppearanceVersion.id == version_id, m.AppearanceVersion.companion_id == c.id))
    if v is None:
        raise fail(404, "not_found")
    if v.status != "pending":
        raise fail(409, "conflict")
    return v


@router.post("/{companion_id}/appearance/{version_id}/approve")
def approve_appearance(
    companion_id: str, version_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)
) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    require_subject(c, user)
    require_likeness(c)
    v = _pending_version(db, c, version_id)
    v.status = "approved"
    v.decided_at = c.updated_at = utcnow()
    db.commit()
    return _out(db, c, user)


@router.post("/{companion_id}/appearance/{version_id}/reject")
def reject_appearance(
    companion_id: str,
    version_id: str,
    body: RejectIn | None = Body(default=None),
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    require_subject(c, user)
    v = _pending_version(db, c, version_id)
    v.status = "rejected"
    v.reject_reason = body.reason if body else None
    v.decided_at = c.updated_at = utcnow()
    db.commit()
    return _out(db, c, user)


# ---------------------------------------------------------------- persona

@router.put("/{companion_id}/persona")
def put_persona(
    companion_id: str, body: PersonaIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)
) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    data = body.model_dump(exclude_none=True)
    if user.id == c.subject_id:
        c.persona = data
    else:
        c.pending_persona = data
        c.pending_persona_by = user.id
    c.updated_at = utcnow()
    db.commit()
    return _out(db, c, user)


@router.post("/{companion_id}/persona/approve")
def approve_persona(companion_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    require_subject(c, user)
    if c.pending_persona is None:
        raise fail(404, "not_found")
    c.persona, c.pending_persona, c.pending_persona_by = c.pending_persona, None, None
    c.updated_at = utcnow()
    db.commit()
    return _out(db, c, user)


@router.post("/{companion_id}/persona/reject")
def reject_persona(companion_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    require_subject(c, user)
    if c.pending_persona is None:
        raise fail(404, "not_found")
    c.pending_persona, c.pending_persona_by = None, None
    c.updated_at = utcnow()
    db.commit()
    return _out(db, c, user)


# ---------------------------------------------------------------- photos

@router.get("/{companion_id}/photos")
def list_photos(companion_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    c = load_companion(db, companion_id, user)
    rows = db.scalars(select(m.Photo).where(m.Photo.companion_id == c.id).order_by(m.Photo.seq)).all()
    return [photo_out(p) for p in rows]


@router.post("/{companion_id}/photos")
def upload_photo(
    companion_id: str,
    file: UploadFile = File(...),
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    require_likeness(c)
    count = db.scalar(select(func.count()).select_from(m.Photo).where(m.Photo.companion_id == c.id)) or 0
    if count >= MAX_PHOTOS:
        raise fail(409, "photo_limit_reached")
    data = read_upload(file)
    content_type = sniff_image_type(data)
    if content_type is None:
        raise fail(422, "unsupported_media_type")
    blob = store.put(data)
    photo = m.Photo(companion_id=c.id, uploaded_by=user.id, content_type=content_type, blob_name=blob, size_bytes=len(data))
    db.add(photo)
    c.updated_at = utcnow()
    try:
        db.commit()
    except Exception:
        store.delete(blob)
        raise
    return photo_out(photo)


def _photo(db: Session, c: m.Companion, photo_id: str) -> m.Photo:
    p = db.scalar(select(m.Photo).where(m.Photo.id == photo_id, m.Photo.companion_id == c.id))
    if p is None:
        raise fail(404, "not_found")
    return p


@router.get("/{companion_id}/photos/{photo_id}")
def get_photo(
    companion_id: str,
    photo_id: str,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
) -> Response:
    c = load_companion(db, companion_id, user)
    p = _photo(db, c, photo_id)
    try:
        data = store.get(p.blob_name)
    except KeyError:
        raise fail(404, "not_found")
    return Response(content=data, media_type=p.content_type, headers={"Cache-Control": "private, no-store"})


@router.delete("/{companion_id}/photos/{photo_id}", status_code=204)
def delete_photo(
    companion_id: str,
    photo_id: str,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
) -> Response:
    c = load_companion(db, companion_id, user)
    p = _photo(db, c, photo_id)
    if user.id not in (c.subject_id, p.uploaded_by):
        raise fail(403, "forbidden")
    blob = p.blob_name
    db.delete(p)
    c.updated_at = utcnow()
    commit_and_delete_blobs(db, store, [blob])
    return Response(status_code=204)


# ---------------------------------------------------------------- likeness

@router.post("/{companion_id}/likeness/generate", status_code=202)
def generate_likeness(
    companion_id: str,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
    providers: Providers = Depends(get_providers),
) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    require_likeness(c)
    if not providers.likeness.configured:
        raise fail(503, "likeness_provider_not_configured")
    approved = approved_appearance(db, c.id)
    if approved is None:
        raise fail(409, "companion_not_active")
    photos = db.scalars(select(m.Photo).where(m.Photo.companion_id == c.id).order_by(m.Photo.seq)).all()
    job = LikenessJob(companion_id=c.id, appearance=dict(approved.data),
                      photos=[(p.content_type, store.get(p.blob_name)) for p in photos])
    c.likeness_status = providers.likeness.start(job)
    c.updated_at = utcnow()
    db.commit()
    return {"status": "processing"}


@router.get("/{companion_id}/likeness")
def get_likeness(
    companion_id: str,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
    providers: Providers = Depends(get_providers),
) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    if not providers.likeness.configured:
        return {"status": "unavailable", "assets": []}
    if not c.consent_likeness:
        return {"status": "none", "assets": []}
    if c.likeness_status == "processing":
        status, new_assets = providers.likeness.poll(c.id)
        for asset in new_assets:
            db.add(m.LikenessAsset(companion_id=c.id, kind=asset.kind, content_type=asset.content_type,
                                   blob_name=store.put(asset.data)))
        c.likeness_status = status
        db.commit()
    assets = db.scalars(select(m.LikenessAsset).where(m.LikenessAsset.companion_id == c.id).order_by(m.LikenessAsset.seq)).all()
    return {
        "status": c.likeness_status,
        "assets": [{"id": a.id, "kind": a.kind, "content_type": a.content_type, "created_at": iso(a.created_at)} for a in assets],
    }


# ---------------------------------------------------------------- voice

@router.post("/{companion_id}/voice/samples")
def upload_voice_sample(
    companion_id: str,
    file: UploadFile = File(...),
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
    providers: Providers = Depends(get_providers),
) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    require_subject(c, user)
    if not c.consent_voice:
        raise fail(403, "voice_consent_required")
    count = db.scalar(select(func.count()).select_from(m.VoiceSample).where(m.VoiceSample.companion_id == c.id)) or 0
    if count >= MAX_VOICE_SAMPLES:
        raise fail(409, "sample_limit_reached")
    data = read_upload(file)
    declared = (file.content_type or "").split(";")[0].strip().lower()
    # Accept a declared audio/* type, or a generic part whose bytes are a known audio container.
    content_type = declared if declared.startswith("audio/") else sniff_audio_type(data)
    if content_type is None:
        raise fail(422, "unsupported_media_type")
    blob = store.put(data)
    db.add(m.VoiceSample(companion_id=c.id, content_type=content_type, blob_name=blob, size_bytes=len(data)))
    db.flush()
    if providers.voice.configured:
        samples = db.scalars(select(m.VoiceSample).where(m.VoiceSample.companion_id == c.id).order_by(m.VoiceSample.seq)).all()
        c.voice_status = providers.voice.add_samples(c.id, [(s.content_type, store.get(s.blob_name)) for s in samples])
    c.updated_at = utcnow()
    try:
        db.commit()
    except Exception:
        store.delete(blob)
        raise
    return {"status": c.voice_status, "samples": count + 1}


@router.post("/{companion_id}/voice/speak")
def speak(
    companion_id: str,
    body: SpeakIn,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    providers: Providers = Depends(get_providers),
) -> Response:
    c = load_companion(db, companion_id, user)
    require_viewer(c, user)
    if not providers.voice.configured:
        raise fail(503, "voice_provider_not_configured")
    if not c.consent_voice or c.voice_status != "ready":
        raise fail(409, "voice_not_ready")
    audio = providers.voice.speak(c.id, body.text)
    return Response(content=audio, media_type="audio/mpeg", headers={"Cache-Control": "private, no-store"})
