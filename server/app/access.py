"""Role/consent guards and small helpers shared by routers."""
from __future__ import annotations

from fastapi import HTTPException, UploadFile
from sqlalchemy.orm import Session

from . import models as m
from .cleanup import couple_of
from .storage import MAX_UPLOAD_BYTES, BlobStore


def fail(status: int, code: str) -> HTTPException:
    return HTTPException(status_code=status, detail=code)


def require_couple(db: Session, user: m.User) -> m.Couple:
    couple = couple_of(db, user.id)
    if couple is None:
        raise fail(404, "not_paired")
    return couple


def load_companion(db: Session, companion_id: str, user: m.User) -> m.Companion:
    c = db.get(m.Companion, companion_id)
    if c is None or user.id not in (c.subject_id, c.viewer_id):
        raise fail(404, "not_found")
    return c


def require_subject(c: m.Companion, user: m.User) -> None:
    if user.id != c.subject_id:
        raise fail(403, "forbidden")


def require_viewer(c: m.Companion, user: m.User) -> None:
    if user.id != c.viewer_id:
        raise fail(403, "forbidden")


def require_likeness(c: m.Companion) -> None:
    if not c.consent_likeness:
        raise fail(403, "likeness_consent_required")


def commit_and_delete_blobs(db: Session, store: BlobStore, blobs: list[str]) -> None:
    db.commit()
    for name in blobs:
        store.delete(name)


def read_upload(file: UploadFile, max_bytes: int = MAX_UPLOAD_BYTES) -> bytes:
    data = file.file.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise fail(422, "file_too_large")
    if not data:
        raise fail(422, "empty_file")
    return data
