"""Voice (clone + TTS) provider interface.

A real provider subclasses `VoiceProvider` and is enabled with
HER_VOICE_PROVIDER="package.module:ClassName" (constructed with no arguments). It is only called
after the subject's voice consent and must delete the cloned voice in `delete_voice()`.
"""
from __future__ import annotations

from abc import ABC, abstractmethod


class VoiceProvider(ABC):
    configured: bool = True

    @abstractmethod
    def add_samples(self, companion_id: str, samples: list[tuple[str, bytes]]) -> str:
        """Receive all current samples (content_type, bytes). Returns "none", "processing" or "ready"."""

    @abstractmethod
    def speak(self, companion_id: str, text: str) -> bytes:
        """Return audio/mpeg bytes in the companion's voice."""

    @abstractmethod
    def delete_voice(self, companion_id: str) -> None:
        """Permanently delete the cloned voice and any copies of the samples."""


class NotConfiguredVoiceProvider(VoiceProvider):
    configured = False

    def add_samples(self, companion_id: str, samples: list[tuple[str, bytes]]) -> str:
        return "none"  # samples are still stored (encrypted) and counted by the server

    def speak(self, companion_id: str, text: str) -> bytes:
        raise NotImplementedError("voice_provider_not_configured")

    def delete_voice(self, companion_id: str) -> None:
        return None
