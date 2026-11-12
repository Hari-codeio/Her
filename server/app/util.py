"""Small shared helpers: ids, UTC time, ISO formatting."""
from __future__ import annotations

import uuid
from datetime import date, datetime, timezone


def new_id() -> str:
    return uuid.uuid4().hex


def utcnow() -> datetime:
    """Naive UTC datetime (SQLite stores naive values; everything server-side is UTC)."""
    return datetime.now(timezone.utc).replace(tzinfo=None)


def iso(dt: datetime | None) -> str | None:
    if dt is None:
        return None
    if dt.tzinfo is not None:
        dt = dt.astimezone(timezone.utc).replace(tzinfo=None)
    return dt.replace(microsecond=0).isoformat() + "Z"


def iso_date(d: date | None) -> str | None:
    return d.isoformat() if d is not None else None
