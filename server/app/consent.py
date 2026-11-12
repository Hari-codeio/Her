"""Consent rules from docs/API.md "Roles and consent".

- status derivation
- applying a consent change: ledger rows with the exact statement text, plus the deletions that
  revoking `likeness` or `voice` requires.
"""
from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import models as m
from .providers import Providers
from .config import CONSENT_SCOPES, CONSENT_STATEMENT_VERSION, CONSENT_STATEMENTS
from .util import utcnow


def approved_appearance(db: Session, companion_id: str) -> m.AppearanceVersion | None:
    return db.scalar(
        select(m.AppearanceVersion)
        .where(m.AppearanceVersion.companion_id == companion_id, m.AppearanceVersion.status == "approved")
        .order_by(m.AppearanceVersion.decided_at.desc(), m.AppearanceVersion.seq.desc())
        .limit(1)
    )


def pending_appearance(db: Session, companion_id: str) -> m.AppearanceVersion | None:
    return db.scalar(
        select(m.AppearanceVersion)
        .where(m.AppearanceVersion.companion_id == companion_id, m.AppearanceVersion.status == "pending")
        .order_by(m.AppearanceVersion.seq.desc())
        .limit(1)
    )


def companion_status(db: Session, c: m.Companion) -> str:
    if not c.consent_likeness:
        return "revoked" if c.likeness_ever_granted else "awaiting_consent"
    return "active" if approved_appearance(db, c.id) is not None else "draft"


def apply_consent_change(
    db: Session, providers: Providers, c: m.Companion, actor_id: str, changes: dict[str, bool | None]
) -> list[str]:
    """Apply requested consent values. Appends one ledger row per scope whose value actually
    changes and performs the required deletions. Returns blob names to delete after commit."""
    blobs: list[str] = []
    now = utcnow()
    for scope in CONSENT_SCOPES:
        if scope not in changes or changes[scope] is None:
            continue
        new_value = bool(changes[scope])
        attr = f"consent_{scope}"
        if getattr(c, attr) == new_value:
            continue
        setattr(c, attr, new_value)
        db.add(
            m.ConsentEvent(
                companion_id=c.id,
                actor_id=actor_id,
                scope=scope,
                granted=new_value,
                statement=CONSENT_STATEMENTS[scope],
                statement_version=CONSENT_STATEMENT_VERSION,
                created_at=now,
            )
        )
        if scope == "likeness":
            if new_value:
                c.likeness_ever_granted = True
            else:
                blobs += delete_likeness_data(db, providers, c)
        elif scope == "voice" and not new_value:
            blobs += delete_voice_data(db, providers, c)
        # personality: nothing is deleted; the persona simply stops being sent to the model.
        # ai_training: recorded only; nothing in this codebase trains on user data.
    c.updated_at = now
    return blobs


def delete_likeness_data(db: Session, providers: Providers, c: m.Companion) -> list[str]:
    """Revoking likeness: photos, appearance versions, likeness renders and the wardrobe
    (the character's clothes) are permanently deleted. Returns blob names."""
    blobs: list[str] = []
    for photo in db.scalars(select(m.Photo).where(m.Photo.companion_id == c.id)).all():
        blobs.append(photo.blob_name)
        db.delete(photo)
    for asset in db.scalars(select(m.LikenessAsset).where(m.LikenessAsset.companion_id == c.id)).all():
        blobs.append(asset.blob_name)
        db.delete(asset)
    for version in db.scalars(select(m.AppearanceVersion).where(m.AppearanceVersion.companion_id == c.id)).all():
        db.delete(version)
    for item in db.scalars(select(m.WardrobeItem).where(m.WardrobeItem.companion_id == c.id)).all():
        db.delete(item)
    c.likeness_status = "none"
    providers.likeness.delete(c.id)
    return blobs


def delete_voice_data(db: Session, providers: Providers, c: m.Companion) -> list[str]:
    """Revoking voice: samples and any cloned voice are permanently deleted. Returns blob names."""
    blobs: list[str] = []
    for sample in db.scalars(select(m.VoiceSample).where(m.VoiceSample.companion_id == c.id)).all():
        blobs.append(sample.blob_name)
        db.delete(sample)
    c.voice_status = "none"
    providers.voice.delete_voice(c.id)
    return blobs
