"""Pluggable likeness/voice providers. Defaults are "not configured"."""
from __future__ import annotations

import importlib
from dataclasses import dataclass
from typing import Any

from fastapi import Request

from .likeness import LikenessProvider, NotConfiguredLikenessProvider
from .voice import NotConfiguredVoiceProvider, VoiceProvider


@dataclass
class Providers:
    likeness: LikenessProvider
    voice: VoiceProvider


def _load(path: str | None, default: type) -> Any:
    """Instantiate "package.module:ClassName", or the default when unset."""
    if not path:
        return default()
    module_name, _, class_name = path.partition(":")
    return getattr(importlib.import_module(module_name), class_name)()


def build_providers(likeness_path: str | None, voice_path: str | None) -> Providers:
    return Providers(
        likeness=_load(likeness_path, NotConfiguredLikenessProvider),
        voice=_load(voice_path, NotConfiguredVoiceProvider),
    )


def get_providers(request: Request) -> Providers:
    return request.app.state.providers
