from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Response
from sqlalchemy.orm import Session

from .. import models as m
from ..access import commit_and_delete_blobs
from ..auth import current_user, generate_token, hash_token
from ..cleanup import couple_of, delete_user
from ..deps import get_db, get_store
from ..providers import Providers, get_providers
from ..schemas import MePatch, RegisterIn, couple_out, user_out
from ..storage import BlobStore

router = APIRouter(prefix="/v1")


@router.post("/auth/register", status_code=201)
def register(body: RegisterIn, db: Session = Depends(get_db)) -> dict[str, Any]:
    user = m.User(display_name=body.display_name)
    db.add(user)
    db.flush()
    token = generate_token()
    db.add(m.AuthToken(user_id=user.id, token_hash=hash_token(token)))
    db.commit()
    return {"user": user_out(user), "token": token}


@router.get("/me")
def get_me(user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    couple = couple_of(db, user.id)
    partner = None
    if couple is not None:
        partner_id = couple.user_b_id if couple.user_a_id == user.id else couple.user_a_id
        partner = db.get(m.User, partner_id)
    return {
        "user": user_out(user),
        "couple": couple_out(db, couple) if couple else None,
        "partner": user_out(partner) if partner else None,
    }


@router.patch("/me")
def patch_me(body: MePatch, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    if "display_name" in body.model_fields_set:
        user.display_name = body.display_name
        db.commit()
    return user_out(user)


@router.delete("/me", status_code=204)
def delete_me(
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    store: BlobStore = Depends(get_store),
    providers: Providers = Depends(get_providers),
) -> Response:
    blobs = delete_user(db, providers, user)
    commit_and_delete_blobs(db, store, blobs)
    return Response(status_code=204)
