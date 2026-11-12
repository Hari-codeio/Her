"""Hard deletion of companions, couples and accounts (DB rows and encrypted blob files).

Callers collect blob names, commit the DB transaction, then call `store.delete` for each name, so
a failed commit never leaves rows pointing at files that are already gone.
"""
from __future__ import annotations

from sqlalchemy import delete, or_, select
from sqlalchemy.orm import Session

from . import models as m
from .consent import delete_likeness_data, delete_voice_data
from .providers import Providers


def delete_companion(db: Session, providers: Providers, c: m.Companion) -> list[str]:
    blobs = delete_likeness_data(db, providers, c) + delete_voice_data(db, providers, c)
    for model in (m.ChatMessage, m.MemorySuggestion, m.ConsentEvent):
        db.execute(delete(model).where(model.companion_id == c.id))
    db.flush()
    db.delete(c)
    return blobs


def delete_couple(db: Session, providers: Providers, couple: m.Couple) -> list[str]:
    blobs: list[str] = []
    for c in db.scalars(select(m.Companion).where(m.Companion.couple_id == couple.id)).all():
        blobs += delete_companion(db, providers, c)
    for memory in db.scalars(select(m.Memory).where(m.Memory.couple_id == couple.id)).all():
        if memory.photo_blob:
            blobs.append(memory.photo_blob)
        db.delete(memory)
    db.execute(delete(m.Occasion).where(m.Occasion.couple_id == couple.id))
    db.flush()
    db.delete(couple)
    return blobs


def couple_of(db: Session, user_id: str) -> m.Couple | None:
    return db.scalar(select(m.Couple).where(or_(m.Couple.user_a_id == user_id, m.Couple.user_b_id == user_id)))


def delete_user(db: Session, providers: Providers, user: m.User) -> list[str]:
    blobs: list[str] = []
    couple = couple_of(db, user.id)
    if couple is not None:
        blobs += delete_couple(db, providers, couple)
    db.execute(delete(m.Invite).where(m.Invite.inviter_id == user.id))
    db.execute(delete(m.AuthToken).where(m.AuthToken.user_id == user.id))
    db.flush()
    db.delete(user)
    return blobs
