from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Response
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from .. import models as m
from ..access import fail, load_companion, require_likeness, require_subject
from ..auth import current_user
from ..deps import get_db
from ..schemas import WardrobeApproveIn, WardrobeIn, WardrobePatch, wardrobe_out

router = APIRouter(prefix="/v1/companions/{companion_id}/wardrobe")


def _item(db: Session, c: m.Companion, item_id: str) -> m.WardrobeItem:
    item = db.scalar(select(m.WardrobeItem).where(m.WardrobeItem.id == item_id, m.WardrobeItem.companion_id == c.id))
    if item is None:
        raise fail(404, "not_found")
    return item


def _visible(c: m.Companion, user: m.User, item: m.WardrobeItem) -> bool:
    return user.id == c.subject_id or item.approved or item.added_by == user.id


@router.get("")
def list_items(companion_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    c = load_companion(db, companion_id, user)
    query = select(m.WardrobeItem).where(m.WardrobeItem.companion_id == c.id).order_by(m.WardrobeItem.seq)
    if user.id == c.viewer_id:
        if not c.consent_likeness:
            return []
        query = query.where(or_(m.WardrobeItem.approved.is_(True), m.WardrobeItem.added_by == user.id))
    return [wardrobe_out(i) for i in db.scalars(query).all()]


@router.post("", status_code=201)
def add_item(
    companion_id: str, body: WardrobeIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)
) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    require_likeness(c)
    item = m.WardrobeItem(companion_id=c.id, approved=user.id == c.subject_id, added_by=user.id, **body.model_dump())
    db.add(item)
    db.commit()
    return wardrobe_out(item)


@router.patch("/{item_id}")
def patch_item(
    companion_id: str,
    item_id: str,
    body: WardrobePatch,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
) -> dict[str, Any]:
    c = load_companion(db, companion_id, user)
    require_likeness(c)
    item = _item(db, c, item_id)
    if not _visible(c, user, item):
        raise fail(404, "not_found")
    if user.id not in (c.subject_id, item.added_by):
        raise fail(403, "forbidden")
    for key, value in body.model_dump(exclude_unset=True).items():
        setattr(item, key, value)
    if user.id != c.subject_id:
        item.approved = False  # a viewer edit needs the subject's approval again
    db.commit()
    return wardrobe_out(item)


@router.delete("/{item_id}", status_code=204)
def delete_item(
    companion_id: str, item_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)
) -> Response:
    c = load_companion(db, companion_id, user)
    item = _item(db, c, item_id)
    if not _visible(c, user, item):
        raise fail(404, "not_found")
    if user.id not in (c.subject_id, item.added_by):
        raise fail(403, "forbidden")
    db.delete(item)
    db.commit()
    return Response(status_code=204)


@router.post("/{item_id}/approve", response_model=None)
def approve_item(
    companion_id: str,
    item_id: str,
    body: WardrobeApproveIn,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
) -> dict[str, Any] | Response:
    c = load_companion(db, companion_id, user)
    require_subject(c, user)
    require_likeness(c)
    item = _item(db, c, item_id)
    if not body.approved:
        db.delete(item)
        db.commit()
        return Response(status_code=204)
    item.approved = True
    db.commit()
    return wardrobe_out(item)
