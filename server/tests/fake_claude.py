"""A scripted stand-in for `anthropic.Anthropic()` covering what the server uses:
`client.beta.messages.stream(...)` (context manager, iterable events, get_final_message) and
`client.beta.messages.create(...)`."""
from __future__ import annotations

import copy
from dataclasses import dataclass, field
from types import SimpleNamespace
from typing import Any


def text_block(text: str) -> SimpleNamespace:
    return SimpleNamespace(type="text", text=text)


def tool_use_block(tool_id: str, tool_input: Any, name: str = "suggest_memory") -> SimpleNamespace:
    return SimpleNamespace(type="tool_use", id=tool_id, name=name, input=tool_input)


@dataclass
class Turn:
    chunks: list[str] = field(default_factory=list)
    stop_reason: str = "end_turn"
    tool_uses: list[SimpleNamespace] = field(default_factory=list)
    raise_value_error: bool = False  # simulates unparseable tool-input JSON mid-stream
    exception: BaseException | None = None  # raised when the request is made


def text_turn(*chunks: str) -> Turn:
    return Turn(chunks=list(chunks))


def tool_turn(*chunks: str, inputs: list[Any]) -> Turn:
    return Turn(chunks=list(chunks), stop_reason="tool_use",
                tool_uses=[tool_use_block(f"toolu_{i}", inp) for i, inp in enumerate(inputs)])


def refusal_turn(*chunks: str) -> Turn:
    return Turn(chunks=list(chunks), stop_reason="refusal")


class _Stream:
    def __init__(self, turn: Turn):
        self.turn = turn

    def __enter__(self) -> "_Stream":
        return self

    def __exit__(self, *exc: Any) -> bool:
        return False

    def __iter__(self):
        yield SimpleNamespace(type="message_start")
        for chunk in self.turn.chunks:
            yield SimpleNamespace(type="text", text=chunk, snapshot="")
        if self.turn.raise_value_error:
            raise ValueError("Unable to parse tool parameter JSON from model.")
        yield SimpleNamespace(type="message_stop")

    def get_final_message(self) -> SimpleNamespace:
        content = []
        if self.turn.chunks:
            content.append(text_block("".join(self.turn.chunks)))
        content += self.turn.tool_uses
        return SimpleNamespace(stop_reason=self.turn.stop_reason, content=content)


class FakeClaude:
    def __init__(self, turns: list[Turn] | None = None, greeting: Turn | None = None):
        self.turns = list(turns or [])
        self.greeting = greeting or text_turn("Hey you! Cozy evening at the café.")
        self.stream_calls: list[dict[str, Any]] = []
        self.create_calls: list[dict[str, Any]] = []
        self.beta = SimpleNamespace(messages=self)

    def _record(self, store: list, kwargs: dict[str, Any]) -> None:
        recorded = dict(kwargs)
        recorded["messages"] = copy.copy(kwargs["messages"])  # the server appends to its own list later
        store.append(recorded)

    # client.beta.messages.stream(...)
    def stream(self, **kwargs: Any) -> _Stream:
        self._record(self.stream_calls, kwargs)
        if not self.turns:
            raise AssertionError("FakeClaude: no scripted turn left")
        turn = self.turns.pop(0)
        if turn.exception is not None:
            raise turn.exception
        return _Stream(turn)

    # client.beta.messages.create(...)
    def create(self, **kwargs: Any) -> SimpleNamespace:
        self._record(self.create_calls, kwargs)
        if self.greeting.exception is not None:
            raise self.greeting.exception
        return _Stream(self.greeting).get_final_message()
