"""Opaque bearer tokens. Only a SHA-256 hash of each token is stored."""
from __future__ import annotations

import hashlib
import secrets

from fastapi import Depends, Header, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from .deps import get_db
from .models import AuthToken, User


def generate_token() -> str:
    return secrets.token_urlsafe(32)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _unauthorized() -> HTTPException:
    return HTTPException(status_code=401, detail="unauthorized", headers={"WWW-Authenticate": "Bearer"})


def current_user(
    authorization: str | None = Header(default=None),
    db: Session = Depends(get_db),
) -> User:
    if not authorization:
        raise _unauthorized()
    scheme, _, token = authorization.partition(" ")
    token = token.strip()
    if scheme.lower() != "bearer" or not token:
        raise _unauthorized()
    row = db.scalar(select(AuthToken).where(AuthToken.token_hash == hash_token(token)))
    if row is None:
        raise _unauthorized()
    user = db.get(User, row.user_id)
    if user is None:
        raise _unauthorized()
    return user
