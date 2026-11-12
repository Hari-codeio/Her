"""FastAPI dependencies shared by the routers."""
from __future__ import annotations

from collections.abc import Iterator

from fastapi import Request
from sqlalchemy.orm import Session

from .config import Settings
from .ratelimit import RateLimiter
from .storage import BlobStore


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_db(request: Request) -> Iterator[Session]:
    session: Session = request.app.state.session_factory()
    try:
        yield session
    finally:
        session.close()


def get_store(request: Request) -> BlobStore:
    return request.app.state.blob_store


def get_rate_limiter(request: Request) -> RateLimiter:
    return request.app.state.chat_rate_limiter
