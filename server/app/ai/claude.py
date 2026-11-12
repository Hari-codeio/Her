"""Claude calls for /chat (streaming, with the suggest_memory tool loop) and /greeting.

Uses the official `anthropic` SDK through the beta namespace so server-side refusal fallbacks
(`fallbacks="default"`, beta `server-side-fallback-2026-07-01`) are on for every request.
"""
from __future__ import annotations

import logging
import threading
from collections.abc import Callable, Iterator
from typing import Any

import anthropic

from .prompts import TOOLS

log = logging.getLogger(__name__)

FALLBACK_BETA = "server-side-fallback-2026-07-01"
CHAT_MAX_TOKENS = 8000
GREETING_MAX_TOKENS = 2000
MAX_TOOL_ITERATIONS = 3
MAX_JSON_RETRIES = 2
TOOL_RESULT_OK = "Saved as a suggestion; the user will confirm."
TOOL_RESULT_INVALID = "Invalid input: `text` must be a non-empty string of at most 200 characters. Nothing was saved."

_client: anthropic.Anthropic | None = None
_client_lock = threading.Lock()


def get_claude_client() -> Any:
    """FastAPI dependency. Builds `anthropic.Anthropic()` lazily (credentials come from
    ANTHROPIC_API_KEY or an `ant auth login` profile, so the server starts without either).
    Returns None if the client cannot be constructed. Tests override this dependency."""
    global _client
    with _client_lock:
        if _client is None:
            try:
                _client = anthropic.Anthropic()
            except Exception as exc:  # noqa: BLE001 - e.g. a malformed credentials profile
                log.warning("could not construct the Anthropic client: %s", exc.__class__.__name__)
                return None
        return _client


def claude_configured(client: Any) -> bool:
    if client is None:
        return False
    if not isinstance(client, anthropic.Anthropic):
        return True  # an injected client (tests, custom wiring)
    try:
        return bool(client.api_key or client.auth_token or client.credentials)
    except Exception:  # pragma: no cover - defensive
        return False


class ChatError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def error_code(exc: BaseException) -> str:
    if isinstance(exc, anthropic.RateLimitError):
        return "rate_limited"
    if isinstance(exc, (anthropic.AuthenticationError, anthropic.PermissionDeniedError)):
        return "chat_unavailable"
    if isinstance(exc, (anthropic.APIConnectionError, anthropic.APIStatusError)):
        return "chat_unavailable"
    return "chat_unavailable"


def system_param(stable_block: str) -> list[dict[str, Any]]:
    return [{"type": "text", "text": stable_block, "cache_control": {"type": "ephemeral"}}]


def _common_params(model: str, stable_block: str, max_tokens: int) -> dict[str, Any]:
    return {
        "model": model,
        "max_tokens": max_tokens,
        "betas": [FALLBACK_BETA],
        "fallbacks": "default",
        "output_config": {"effort": "low"},
        "system": system_param(stable_block),
        "tools": TOOLS,
    }


def _block_type(block: Any) -> str | None:
    return block.get("type") if isinstance(block, dict) else getattr(block, "type", None)


def echo_content(content: list[Any]) -> list[Any]:
    """Assistant content to send back in the next request.

    Unchanged, except after a mid-output server-side fallback: blocks the declined model produced
    before the final `fallback` block that may not be echoed (thinking, redacted_thinking,
    tool_use) are omitted, as the fallback docs require. The `fallback` block stays in place."""
    last_fallback = max((i for i, b in enumerate(content) if _block_type(b) == "fallback"), default=-1)
    if last_fallback < 0:
        return list(content)
    dropped = {"thinking", "redacted_thinking", "tool_use"}
    return [b for i, b in enumerate(content) if i > last_fallback or _block_type(b) not in dropped]


def valid_suggestion_text(tool_input: Any) -> str | None:
    if isinstance(tool_input, dict) and isinstance(tool_input.get("text"), str):
        text = tool_input["text"].strip()
        if text and len(tool_input["text"]) <= 200:
            return text
    return None


Event = tuple[str, dict[str, Any]]


def stream_reply(
    client: Any,
    model: str,
    stable_block: str,
    messages: list[dict[str, Any]],
    on_suggestion: Callable[[str], dict[str, Any]],
) -> Iterator[Event]:
    """Yield ("delta", {"text"}), ("memory_suggestion", MemorySuggestion), and finally exactly one
    of ("final", {"text": full_companion_text}) or ("error", {"detail": code})."""
    params = _common_params(model, stable_block, CHAT_MAX_TOKENS)
    messages = list(messages)
    streamed: list[str] = []
    json_retries = 0
    iterations = 0
    try:
        while iterations < MAX_TOOL_ITERATIONS:
            try:
                with client.beta.messages.stream(messages=messages, **params) as stream:
                    for event in stream:
                        if event.type == "text" and event.text:
                            streamed.append(event.text)
                            yield "delta", {"text": event.text}
                    response = stream.get_final_message()
            except ValueError:
                # Tool-input JSON the SDK could not parse at all: there is no tool_use_id to
                # answer, so re-issue the same turn (bounded). API errors aren't ValueError.
                json_retries += 1
                if json_retries > MAX_JSON_RETRIES:
                    log.warning("giving up after %d unparseable tool inputs", json_retries)
                    yield "error", {"detail": "chat_unavailable"}
                    return
                continue
            json_retries = 0
            iterations += 1

            if response.stop_reason == "refusal":
                yield "error", {"detail": "declined"}
                return

            content = echo_content(list(response.content))
            tool_uses = [b for b in content if _block_type(b) == "tool_use"]
            if response.stop_reason != "tool_use" or not tool_uses:
                # end_turn / stop_sequence, or max_tokens (a truncated tool input is never run).
                break

            results = []
            for block in tool_uses:
                text = valid_suggestion_text(block.input) if block.name == "suggest_memory" else None
                if text is None:
                    results.append({"type": "tool_result", "tool_use_id": block.id, "is_error": True,
                                    "content": TOOL_RESULT_INVALID})
                    continue
                yield "memory_suggestion", on_suggestion(text)
                results.append({"type": "tool_result", "tool_use_id": block.id, "content": TOOL_RESULT_OK})
            messages.append({"role": "assistant", "content": content})
            messages.append({"role": "user", "content": results})
    except Exception as exc:  # noqa: BLE001 - every failure becomes an SSE error event
        code = error_code(exc)
        log.warning("chat call failed (%s): %s", code, exc.__class__.__name__)
        yield "error", {"detail": code}
        return

    reply = "".join(streamed)
    if not reply.strip():  # e.g. only tool calls until the iteration cap: nothing to show or store
        yield "error", {"detail": "chat_unavailable"}
        return
    yield "final", {"text": reply}


def generate_greeting(client: Any, model: str, stable_block: str, messages: list[dict[str, Any]]) -> str:
    """Non-streaming greeting. Raises ChatError on any failure or refusal."""
    params = _common_params(model, stable_block, GREETING_MAX_TOKENS)
    try:
        response = client.beta.messages.create(messages=messages, tool_choice={"type": "none"}, **params)
    except Exception as exc:  # noqa: BLE001
        log.warning("greeting call failed (%s): %s", error_code(exc), exc.__class__.__name__)
        raise ChatError("chat_unavailable") from exc
    if response.stop_reason == "refusal":
        raise ChatError("chat_unavailable")
    text = "".join(b.text for b in response.content if _block_type(b) == "text").strip()
    if not text:
        raise ChatError("chat_unavailable")
    return text
