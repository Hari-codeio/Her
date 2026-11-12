from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends

from ..ai.claude import claude_configured, get_claude_client
from ..config import CONSENT_STATEMENT_VERSION, CONSENT_STATEMENTS
from ..providers import Providers, get_providers

router = APIRouter()


@router.get("/healthz")
def healthz() -> dict[str, bool]:
    return {"ok": True}


@router.get("/v1/config")
def config(client: Any = Depends(get_claude_client), providers: Providers = Depends(get_providers)) -> dict[str, Any]:
    return {
        "chat_available": claude_configured(client),
        "likeness_provider": providers.likeness.configured,
        "voice_provider": providers.voice.configured,
        "consent_statement_version": CONSENT_STATEMENT_VERSION,
        "consent_statements": dict(CONSENT_STATEMENTS),
    }
