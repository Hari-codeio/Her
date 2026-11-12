"""Couple-scoped memories and occasions (either member)."""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, File, Response, UploadFile
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import models as m
from ..access import commit_and_delete_blobs, fail, read_upload, require_couple
from ..auth import current_user
from ..deps import get_db, get_store
from ..schemas import MemoryIn, MemoryPatch, OccasionIn, OccasionPatch, memory_out, occasion_out
from ..storage import BlobStore, sniff_image_type
from ..util import utcnow

router = APIRouter(prefix="/v1")


# ---------------------------------------------------------------- memories

def _memory(db: Session, couple: m.Couple, memory_id: str) -> m.Memory:
    x = db.scalar(select(m.Memory).where(m.Memory.id == memory_id, m.Memory.couple_id == couple.id))
    if x is None:
        raise fail(404, "not_found")
    return x


@router.get("/memories")
def list_memories(user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    couple = require_couple(db, user)
    rows = db.scalars(
        select(m.Memory)
        .where(m.Memory.couple_id == couple.id)
        .order_by(m.Memory.happened_on.is_(None), m.Memory.happened_on.desc(), m.Memory.seq.desc())
    ).all()
    return [memory_out(x) for x in rows]


@router.post("/memories", status_code=201)
def create_memory(body: MemoryIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    couple = require_couple(db, user)
    now = utcnow()
    x = m.Memory(couple_id=couple.id, created_by=user.id, created_at=now, updated_at=now, **body.model_dump())
    db.add(x)
    db.commit()
    return memory_out(x)


@router.patch("/memories/{memory_id}")
def patch_memory(
    memory_id: str, body: MemoryPatch, user: m.User = Depends(current_user), db: Session = Depends(get_db)
) -> dict[str, Any]:
    x = _memory(db, require_couple(db, user), memory_id)
    for key, value in body.model_dump(exclude_unset=True).items():
        setattr(x, key, value)
    x.updated_at = utcnow()
    db.commit()
    return memory_out(x)


@router.delete("/memories/{memory_id}", status_code=204)
def delete_memory(
    memory_id: str,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
) -> Response:
    x = _memory(db, require_couple(db, user), memory_id)
    blobs = [x.photo_blob] if x.photo_blob else []
    db.delete(x)
    commit_and_delete_blobs(db, store, blobs)
    return Response(status_code=204)


@router.put("/memories/{memory_id}/photo")
def put_memory_photo(
    memory_id: str,
    file: UploadFile = File(...),
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
) -> dict[str, Any]:
    x = _memory(db, require_couple(db, user), memory_id)
    data = read_upload(file)
    content_type = sniff_image_type(data)
    if content_type is None:
        raise fail(422, "unsupported_media_type")
    old = x.photo_blob
    x.photo_blob = store.put(data)
    x.photo_content_type = content_type
    x.updated_at = utcnow()
    commit_and_delete_blobs(db, store, [old] if old else [])
    return memory_out(x)


@router.get("/memories/{memory_id}/photo")
def get_memory_photo(
    memory_id: str,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
) -> Response:
    x = _memory(db, require_couple(db, user), memory_id)
    if not x.photo_blob:
        raise fail(404, "not_found")
    try:
        data = store.get(x.photo_blob)
    except KeyError:
        raise fail(404, "not_found")
    return Response(content=data, media_type=x.photo_content_type, headers={"Cache-Control": "private, no-store"})


# ---------------------------------------------------------------- occasions

def _occasion(db: Session, couple: m.Couple, occasion_id: str) -> m.Occasion:
    o = db.scalar(select(m.Occasion).where(m.Occasion.id == occasion_id, m.Occasion.couple_id == couple.id))
    if o is None:
        raise fail(404, "not_found")
    return o


def _check_person(couple: m.Couple, person_user_id: str | None) -> None:
    if person_user_id is not None and person_user_id not in (couple.user_a_id, couple.user_b_id):
        raise fail(422, "person_not_in_couple")


@router.get("/occasions")
def list_occasions(user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    couple = require_couple(db, user)
    rows = db.scalars(select(m.Occasion).where(m.Occasion.couple_id == couple.id).order_by(m.Occasion.date, m.Occasion.seq)).all()
    return [occasion_out(o) for o in rows]


@router.post("/occasions", status_code=201)
def create_occasion(body: OccasionIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    couple = require_couple(db, user)
    _check_person(couple, body.person_user_id)
    o = m.Occasion(couple_id=couple.id, created_by=user.id, **body.model_dump())
    db.add(o)
    db.commit()
    return occasion_out(o)


@router.patch("/occasions/{occasion_id}")
def patch_occasion(
    occasion_id: str, body: OccasionPatch, user: m.User = Depends(current_user), db: Session = Depends(get_db)
) -> dict[str, Any]:
    couple = require_couple(db, user)
    o = _occasion(db, couple, occasion_id)
    changes = body.model_dump(exclude_unset=True)
    if "person_user_id" in changes:
        _check_person(couple, changes["person_user_id"])
    for key, value in changes.items():
        setattr(o, key, value)
    db.commit()
    return occasion_out(o)


@router.delete("/occasions/{occasion_id}", status_code=204)
def delete_occasion(occasion_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> Response:
    o = _occasion(db, require_couple(db, user), occasion_id)
    db.delete(o)
    db.commit()
    return Response(status_code=204)
