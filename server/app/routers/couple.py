from __future__ import annotations

import secrets
from datetime import timedelta
from typing import Any

from fastapi import APIRouter, Depends, Response
from sqlalchemy import delete
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .. import models as m
from ..access import commit_and_delete_blobs, fail, require_couple
from ..auth import current_user
from ..cleanup import couple_of, delete_couple
from ..deps import get_db, get_store
from ..providers import Providers, get_providers
from ..schemas import CouplePatch, JoinIn, couple_out
from ..storage import BlobStore
from ..util import iso, utcnow

router = APIRouter(prefix="/v1/couple")

# Unambiguous alphabet: no 0/O or 1/I.
CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
INVITE_TTL = timedelta(hours=24)


def new_code() -> str:
    raw = "".join(secrets.choice(CODE_ALPHABET) for _ in range(8))
    return f"{raw[:4]}-{raw[4:]}"


def normalize_code(code: str) -> str:
    raw = "".join(ch for ch in code.upper() if ch.isalnum())
    return f"{raw[:4]}-{raw[4:]}" if len(raw) == 8 else code.strip().upper()


@router.post("/invite")
def create_invite(user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    if couple_of(db, user.id) is not None:
        raise fail(409, "already_paired")
    # A new invite replaces any unused one from the same user.
    db.execute(delete(m.Invite).where(m.Invite.inviter_id == user.id, m.Invite.used_at.is_(None)))
    now = utcnow()
    while True:
        code = new_code()
        if db.get(m.Invite, code) is None:
            break
    invite = m.Invite(code=code, inviter_id=user.id, created_at=now, expires_at=now + INVITE_TTL)
    db.add(invite)
    db.commit()
    return {"code": invite.code, "expires_at": iso(invite.expires_at)}


@router.post("/join")
def join(body: JoinIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    if couple_of(db, user.id) is not None:
        raise fail(409, "already_paired")
    invite = db.get(m.Invite, normalize_code(body.code))
    now = utcnow()
    if invite is None or invite.used_at is not None or invite.expires_at <= now:
        raise fail(404, "invalid_code")
    if invite.inviter_id == user.id:
        raise fail(409, "own_code")
    inviter = db.get(m.User, invite.inviter_id)
    if inviter is None or couple_of(db, inviter.id) is not None:
        raise fail(404, "invalid_code")

    invite.used_at = now
    couple = m.Couple(user_a_id=inviter.id, user_b_id=user.id, created_at=now)
    db.add(couple)
    db.flush()
    db.add_all([
        m.Companion(couple_id=couple.id, subject_id=inviter.id, viewer_id=user.id, created_at=now, updated_at=now),
        m.Companion(couple_id=couple.id, subject_id=user.id, viewer_id=inviter.id, created_at=now, updated_at=now),
    ])
    # Any other outstanding invites from either member are now meaningless.
    db.execute(delete(m.Invite).where(m.Invite.inviter_id.in_([inviter.id, user.id]), m.Invite.used_at.is_(None)))
    try:
        db.commit()
    except IntegrityError:  # lost a race with a concurrent join
        db.rollback()
        raise fail(409, "already_paired")
    return couple_out(db, couple)


@router.get("")
def get_couple(user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    return couple_out(db, require_couple(db, user))


@router.patch("")
def patch_couple(body: CouplePatch, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    couple = require_couple(db, user)
    if "anniversary_date" in body.model_fields_set:
        couple.anniversary_date = body.anniversary_date
        db.commit()
    return couple_out(db, couple)


@router.delete("", status_code=204)
def unpair(
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
    providers: Providers = Depends(get_providers),
) -> Response:
    couple = require_couple(db, user)
    blobs = delete_couple(db, providers, couple)
    commit_and_delete_blobs(db, store, blobs)
    return Response(status_code=204)
