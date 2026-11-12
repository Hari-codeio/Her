"""Runtime settings, read from environment variables."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parent.parent

CONSENT_STATEMENT_VERSION = "2026-11-01"

CONSENT_STATEMENTS: dict[str, str] = {
    "likeness": (
        "I agree that my partner can create and see a private AI character that looks like me, "
        "made from photos and appearance details I can review. I approve or reject every version. "
        "I can revoke this at any time, which permanently deletes my photos and the character."
    ),
    "voice": (
        "I agree that recordings of my voice can be used to create a voice for my AI character, "
        "heard only by my partner. I can revoke this at any time, which permanently deletes my "
        "recordings and the voice."
    ),
    "personality": (
        "I agree that the personality details I approve (how I talk, what I like, nicknames) can "
        "shape how my AI character talks with my partner. The character is always labelled as AI "
        "and never claims to be me."
    ),
    "ai_training": (
        "I agree that my photos, voice and conversations may be used to improve AI models. "
        "This is optional and off unless I turn it on."
    ),
}

CONSENT_SCOPES = ("likeness", "voice", "personality", "ai_training")


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    database_url: str
    blob_dir: Path
    storage_key: str | None
    claude_model: str
    chat_rate_limit_per_minute: int
    likeness_provider: str | None
    voice_provider: str | None

    @classmethod
    def from_env(cls) -> "Settings":
        data_dir = Path(os.environ.get("HER_DATA_DIR") or SERVER_DIR / "data")
        database_url = os.environ.get("HER_DATABASE_URL") or f"sqlite:///{(data_dir / 'her.db').as_posix()}"
        blob_dir = Path(os.environ.get("HER_BLOB_DIR") or data_dir / "blobs")
        return cls(
            data_dir=data_dir,
            database_url=database_url,
            blob_dir=blob_dir,
            storage_key=os.environ.get("HER_STORAGE_KEY") or None,
            claude_model=os.environ.get("HER_CLAUDE_MODEL") or "claude-opus-5-5",
            chat_rate_limit_per_minute=int(os.environ.get("HER_CHAT_RATE_LIMIT") or 30),
            likeness_provider=os.environ.get("HER_LIKENESS_PROVIDER") or None,
            voice_provider=os.environ.get("HER_VOICE_PROVIDER") or None,
        )
