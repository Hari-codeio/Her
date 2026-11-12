"""ORM models.

Tables whose rows need a stable insertion order use an integer `seq` primary key plus an opaque
`id` (UUID4 hex) that is the only identifier exposed through the API.
"""
from __future__ import annotations

from datetime import date, datetime
from typing import Any

from sqlalchemy import JSON, Boolean, Date, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base
from .util import new_id, utcnow


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    display_name: Mapped[str] = mapped_column(String(40))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class AuthToken(Base):
    __tablename__ = "auth_tokens"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)  # SHA-256 hex, never the token
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Invite(Base):
    __tablename__ = "invites"

    code: Mapped[str] = mapped_column(String(9), primary_key=True)
    inviter_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    expires_at: Mapped[datetime] = mapped_column(DateTime)
    used_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class Couple(Base):
    __tablename__ = "couples"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    user_a_id: Mapped[str] = mapped_column(ForeignKey("users.id"), unique=True)  # inviter
    user_b_id: Mapped[str] = mapped_column(ForeignKey("users.id"), unique=True)  # joiner
    anniversary_date: Mapped[date | None] = mapped_column(Date, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Companion(Base):
    __tablename__ = "companions"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    couple_id: Mapped[str] = mapped_column(ForeignKey("couples.id"), index=True)
    subject_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    viewer_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    name: Mapped[str | None] = mapped_column(String(40), nullable=True)  # None -> subject.display_name

    consent_likeness: Mapped[bool] = mapped_column(Boolean, default=False)
    consent_voice: Mapped[bool] = mapped_column(Boolean, default=False)
    consent_personality: Mapped[bool] = mapped_column(Boolean, default=False)
    consent_ai_training: Mapped[bool] = mapped_column(Boolean, default=False)
    likeness_ever_granted: Mapped[bool] = mapped_column(Boolean, default=False)

    persona: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)  # approved
    pending_persona: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    pending_persona_by: Mapped[str | None] = mapped_column(String(32), nullable=True)

    voice_status: Mapped[str] = mapped_column(String(16), default="none")  # none|processing|ready
    likeness_status: Mapped[str] = mapped_column(String(16), default="none")  # none|processing|ready

    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class AppearanceVersion(Base):
    __tablename__ = "appearance_versions"

    seq: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    id: Mapped[str] = mapped_column(String(32), unique=True, default=new_id)
    companion_id: Mapped[str] = mapped_column(ForeignKey("companions.id"), index=True)
    data: Mapped[dict[str, Any]] = mapped_column(JSON)
    status: Mapped[str] = mapped_column(String(16))  # pending|approved|rejected
    authored_by: Mapped[str] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    decided_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    reject_reason: Mapped[str | None] = mapped_column(String(500), nullable=True)


class Photo(Base):
    __tablename__ = "photos"

    seq: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    id: Mapped[str] = mapped_column(String(32), unique=True, default=new_id)
    companion_id: Mapped[str] = mapped_column(ForeignKey("companions.id"), index=True)
    uploaded_by: Mapped[str] = mapped_column(ForeignKey("users.id"))
    content_type: Mapped[str] = mapped_column(String(32))
    blob_name: Mapped[str] = mapped_column(String(64))
    size_bytes: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class VoiceSample(Base):
    __tablename__ = "voice_samples"

    seq: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    id: Mapped[str] = mapped_column(String(32), unique=True, default=new_id)
    companion_id: Mapped[str] = mapped_column(ForeignKey("companions.id"), index=True)
    content_type: Mapped[str] = mapped_column(String(64))
    blob_name: Mapped[str] = mapped_column(String(64))
    size_bytes: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class LikenessAsset(Base):
    """A render produced by a likeness provider (none exist until a provider is configured)."""

    __tablename__ = "likeness_assets"

    seq: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    id: Mapped[str] = mapped_column(String(32), unique=True, default=new_id)
    companion_id: Mapped[str] = mapped_column(ForeignKey("companions.id"), index=True)
    kind: Mapped[str] = mapped_column(String(32), default="portrait")
    content_type: Mapped[str] = mapped_column(String(64))
    blob_name: Mapped[str] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class WardrobeItem(Base):
    __tablename__ = "wardrobe_items"

    seq: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    id: Mapped[str] = mapped_column(String(32), unique=True, default=new_id)
    companion_id: Mapped[str] = mapped_column(ForeignKey("companions.id"), index=True)
    name: Mapped[str] = mapped_column(String(100))
    category: Mapped[str] = mapped_column(String(16))
    colors: Mapped[list[str]] = mapped_column(JSON)
    pattern: Mapped[str] = mapped_column(String(16))
    styles: Mapped[list[str]] = mapped_column(JSON)
    seasons: Mapped[list[str]] = mapped_column(JSON)
    warmth: Mapped[int] = mapped_column(Integer)
    approved: Mapped[bool] = mapped_column(Boolean)
    added_by: Mapped[str] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Memory(Base):
    __tablename__ = "memories"

    seq: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    id: Mapped[str] = mapped_column(String(32), unique=True, default=new_id)
    couple_id: Mapped[str] = mapped_column(ForeignKey("couples.id"), index=True)
    kind: Mapped[str] = mapped_column(String(16))
    title: Mapped[str] = mapped_column(String(200))
    body: Mapped[str] = mapped_column(Text, default="")
    happened_on: Mapped[date | None] = mapped_column(Date, nullable=True)
    place: Mapped[str | None] = mapped_column(String(200), nullable=True)
    photo_blob: Mapped[str | None] = mapped_column(String(64), nullable=True)
    photo_content_type: Mapped[str | None] = mapped_column(String(32), nullable=True)
    is_fictional: Mapped[bool] = mapped_column(Boolean, default=False)
    recall_allowed: Mapped[bool] = mapped_column(Boolean, default=True)
    created_by: Mapped[str] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Occasion(Base):
    __tablename__ = "occasions"

    seq: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    id: Mapped[str] = mapped_column(String(32), unique=True, default=new_id)
    couple_id: Mapped[str] = mapped_column(ForeignKey("couples.id"), index=True)
    kind: Mapped[str] = mapped_column(String(16))
    title: Mapped[str] = mapped_column(String(200))
    date: Mapped[date] = mapped_column(Date)
    recurs_yearly: Mapped[bool] = mapped_column(Boolean, default=True)
    person_user_id: Mapped[str | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    companion_may_initiate: Mapped[bool] = mapped_column(Boolean, default=True)
    created_by: Mapped[str] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class ChatMessage(Base):
    __tablename__ = "chat_messages"

    seq: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    id: Mapped[str] = mapped_column(String(32), unique=True, default=new_id)
    companion_id: Mapped[str] = mapped_column(ForeignKey("companions.id"), index=True)
    role: Mapped[str] = mapped_column(String(16))  # user|companion
    text: Mapped[str] = mapped_column(Text)
    kind: Mapped[str] = mapped_column(String(16))  # chat|game|greeting
    game: Mapped[str | None] = mapped_column(String(32), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class MemorySuggestion(Base):
    __tablename__ = "memory_suggestions"

    seq: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    id: Mapped[str] = mapped_column(String(32), unique=True, default=new_id)
    companion_id: Mapped[str] = mapped_column(ForeignKey("companions.id"), index=True)
    text: Mapped[str] = mapped_column(String(200))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class ConsentEvent(Base):
    """Append-only consent ledger. Rows are never updated; they are removed only when the
    companion itself is deleted (account deletion or unpairing)."""

    __tablename__ = "consent_events"

    seq: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    id: Mapped[str] = mapped_column(String(32), unique=True, default=new_id)
    companion_id: Mapped[str] = mapped_column(ForeignKey("companions.id"), index=True)
    actor_id: Mapped[str] = mapped_column(String(32))
    scope: Mapped[str] = mapped_column(String(16))
    granted: Mapped[bool] = mapped_column(Boolean)
    statement: Mapped[str] = mapped_column(Text)
    statement_version: Mapped[str] = mapped_column(String(16))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
