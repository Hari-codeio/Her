"""Encrypted blob storage for photos and voice samples.

Every blob is encrypted with Fernet (AES-128-CBC + HMAC-SHA256) before it touches disk. The key
comes from HER_STORAGE_KEY, or is generated once into <data_dir>/storage.key.
"""
from __future__ import annotations

import logging
import os
from pathlib import Path

from cryptography.fernet import Fernet, InvalidToken

from .util import new_id

log = logging.getLogger(__name__)

MAX_UPLOAD_BYTES = 10 * 1024 * 1024
IMAGE_TYPES = ("image/jpeg", "image/png", "image/webp")


def load_or_create_key(explicit_key: str | None, data_dir: Path) -> bytes:
    if explicit_key:
        key = explicit_key.encode()
        Fernet(key)  # raises ValueError if malformed
        return key
    key_path = data_dir / "storage.key"
    if key_path.exists():
        return key_path.read_bytes().strip()
    data_dir.mkdir(parents=True, exist_ok=True)
    key = Fernet.generate_key()
    # Write with owner-only permissions where the OS supports it.
    fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as fh:
        fh.write(key)
    log.info("Generated a new storage key at %s", key_path)
    return key


class BlobStore:
    def __init__(self, root: Path, key: bytes):
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)
        self._fernet = Fernet(key)

    def _path(self, name: str) -> Path:
        if not name.isalnum():  # names are always uuid hex; refuse anything else
            raise ValueError("bad blob name")
        return self.root / name

    def put(self, data: bytes) -> str:
        name = new_id()
        self._path(name).write_bytes(self._fernet.encrypt(data))
        return name

    def get(self, name: str) -> bytes:
        try:
            return self._fernet.decrypt(self._path(name).read_bytes())
        except (FileNotFoundError, InvalidToken) as exc:
            raise KeyError(name) from exc

    def delete(self, name: str | None) -> None:
        if not name:
            return
        try:
            self._path(name).unlink()
        except FileNotFoundError:
            pass

    def exists(self, name: str) -> bool:
        return self._path(name).exists()


def sniff_audio_type(data: bytes) -> str | None:
    """Recognise common recording containers by their magic bytes."""
    if len(data) >= 12 and data[4:8] == b"ftyp":
        return "audio/mp4"  # m4a / 3gp (Android MediaRecorder)
    if data.startswith(b"OggS"):
        return "audio/ogg"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        return "audio/wav"
    if data.startswith(b"\x1a\x45\xdf\xa3"):
        return "audio/webm"
    if data.startswith(b"#!AMR"):
        return "audio/amr"
    if data.startswith(b"fLaC"):
        return "audio/flac"
    if len(data) >= 2 and data[0] == 0xFF and (data[1] & 0xF6) == 0xF0:
        return "audio/aac"  # ADTS
    if data.startswith(b"ID3") or (len(data) >= 2 and data[0] == 0xFF and (data[1] & 0xE0) == 0xE0):
        return "audio/mpeg"
    return None


def sniff_image_type(data: bytes) -> str | None:
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None
