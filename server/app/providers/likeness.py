"""Likeness (avatar render) provider interface.

A real provider subclasses `LikenessProvider` and is enabled with
HER_LIKENESS_PROVIDER="package.module:ClassName" (constructed with no arguments). Providers receive
decrypted photo bytes and the approved appearance only, are only ever called after the subject's
likeness consent, and must delete everything they hold for a companion in `delete()`.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any


@dataclass
class LikenessJob:
    companion_id: str
    appearance: dict[str, Any]
    photos: list[tuple[str, bytes]]  # (content_type, bytes)


@dataclass
class LikenessAssetData:
    kind: str
    content_type: str
    data: bytes


class LikenessProvider(ABC):
    configured: bool = True

    @abstractmethod
    def start(self, job: LikenessJob) -> str:
        """Start generating renders. Returns "processing" or "ready"."""

    @abstractmethod
    def poll(self, companion_id: str) -> tuple[str, list[LikenessAssetData]]:
        """Return (status, new assets) where status is "processing", "ready" or "none"."""

    @abstractmethod
    def delete(self, companion_id: str) -> None:
        """Permanently delete anything the provider stores for this companion."""


class NotConfiguredLikenessProvider(LikenessProvider):
    configured = False

    def start(self, job: LikenessJob) -> str:
        raise NotImplementedError("likeness_provider_not_configured")

    def poll(self, companion_id: str) -> tuple[str, list[LikenessAssetData]]:
        return "none", []

    def delete(self, companion_id: str) -> None:
        return None
