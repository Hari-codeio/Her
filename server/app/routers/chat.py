"""Chat with the companion (viewer only): history, memory suggestions, greeting and SSE chat."""
from __future__ import annotations

import json
from collections.abc import Iterator
from typing import Any

from fastapi import APIRouter, Body, Depends, Query, Request, Response
from fastapi.responses import StreamingResponse
from sqlalchemy import delete, select
from sqlalchemy.orm import Session, sessionmaker

from .. import models as m
from ..access import fail, load_companion, require_likeness, require_viewer
from ..ai.claude import ChatError, generate_greeting, get_claude_client, stream_reply
from ..ai.prompts import (
    GREETING_INSTRUCTION,
    UPCOMING_WINDOW,
    MemoryFact,
    OccasionFact,
    PromptInputs,
    build_dynamic_context,
    build_stable_block,
    next_occurrence,
)
from ..auth import current_user
from ..config import Settings
from ..consent import companion_status
from ..deps import get_db, get_rate_limiter, get_settings
from ..ratelimit import RateLimiter
from ..schemas import ChatIn, GreetingIn, memory_out, message_out, suggestion_out
from ..util import utcnow

router = APIRouter(prefix="/v1/companions/{companion_id}")

HISTORY_LIMIT = 40
MAX_PROMPT_MEMORIES = 50
MAX_MEMORY_BODY = 500


def _viewer_companion(db: Session, companion_id: str, user: m.User) -> m.Companion:
    c = load_companion(db, companion_id, user)
    require_viewer(c, user)
    return c


def _require_chat_ready(db: Session, c: m.Companion) -> None:
    require_likeness(c)
    if companion_status(db, c) != "active":
        raise fail(409, "companion_not_active")


# ---------------------------------------------------------------- prompt inputs

def build_prompt_inputs(db: Session, c: m.Companion) -> PromptInputs:
    subject = db.get(m.User, c.subject_id)
    viewer = db.get(m.User, c.viewer_id)
    names = {subject.id: subject.display_name, viewer.id: viewer.display_name}

    memories = db.scalars(
        select(m.Memory)
        .where(m.Memory.couple_id == c.couple_id, m.Memory.recall_allowed.is_(True))
        .order_by(m.Memory.happened_on.is_(None), m.Memory.happened_on.desc(), m.Memory.seq.desc())
        .limit(MAX_PROMPT_MEMORIES)
    ).all()

    today = utcnow().date()
    occasions: list[OccasionFact] = []
    for o in db.scalars(select(m.Occasion).where(m.Occasion.couple_id == c.couple_id).order_by(m.Occasion.seq)).all():
        nxt = next_occurrence(o.date, o.recurs_yearly, today)
        if nxt is not None and nxt - today <= UPCOMING_WINDOW:
            occasions.append(OccasionFact(title=o.title, kind=o.kind, next_date=nxt,
                                          person=names.get(o.person_user_id), may_initiate=o.companion_may_initiate))
    occasions.sort(key=lambda f: (f.next_date, f.title))

    return PromptInputs(
        name=c.name or subject.display_name,
        subject=subject.display_name,
        viewer=viewer.display_name,
        persona=c.persona if c.consent_personality else None,
        memories=[
            MemoryFact(title=x.title, body=(x.body or "")[:MAX_MEMORY_BODY], happened_on=x.happened_on,
                       place=x.place, is_fictional=x.is_fictional)
            for x in memories
        ],
        occasions=occasions,
    )


def load_history(db: Session, companion_id: str) -> list[dict[str, Any]]:
    rows = db.scalars(
        select(m.ChatMessage)
        .where(m.ChatMessage.companion_id == companion_id)
        .order_by(m.ChatMessage.seq.desc())
        .limit(HISTORY_LIMIT)
    ).all()
    history = [
        {"role": "user" if r.role == "user" else "assistant", "content": r.text}
        for r in reversed(rows)
        if r.text and r.text.strip()
    ]
    while history and history[0]["role"] != "user":  # the API needs a user turn first
        history.pop(0)
    return history


# ---------------------------------------------------------------- history & suggestions

@router.get("/messages")
def list_messages(
    companion_id: str,
    limit: int = Query(50, ge=1, le=200),
    before: str | None = None,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
) -> list[dict[str, Any]]:
    c = _viewer_companion(db, companion_id, user)
    query = select(m.ChatMessage).where(m.ChatMessage.companion_id == c.id)
    if before:
        anchor = db.scalar(select(m.ChatMessage).where(m.ChatMessage.id == before, m.ChatMessage.companion_id == c.id))
        if anchor is None:
            raise fail(404, "not_found")
        query = query.where(m.ChatMessage.seq < anchor.seq)
    rows = db.scalars(query.order_by(m.ChatMessage.seq.desc()).limit(limit)).all()
    return [message_out(r) for r in reversed(rows)]


@router.delete("/messages", status_code=204)
def clear_messages(companion_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> Response:
    c = _viewer_companion(db, companion_id, user)
    db.execute(delete(m.ChatMessage).where(m.ChatMessage.companion_id == c.id))
    db.commit()
    return Response(status_code=204)


@router.get("/memory-suggestions")
def list_suggestions(companion_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    c = _viewer_companion(db, companion_id, user)
    rows = db.scalars(select(m.MemorySuggestion).where(m.MemorySuggestion.companion_id == c.id).order_by(m.MemorySuggestion.seq)).all()
    return [suggestion_out(s) for s in rows]


def _suggestion(db: Session, c: m.Companion, sid: str) -> m.MemorySuggestion:
    s = db.scalar(select(m.MemorySuggestion).where(m.MemorySuggestion.id == sid, m.MemorySuggestion.companion_id == c.id))
    if s is None:
        raise fail(404, "not_found")
    return s


@router.post("/memory-suggestions/{sid}/accept", status_code=201)
def accept_suggestion(
    companion_id: str, sid: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)
) -> dict[str, Any]:
    c = _viewer_companion(db, companion_id, user)
    s = _suggestion(db, c, sid)
    now = utcnow()
    memory = m.Memory(couple_id=c.couple_id, kind="preference", title=s.text, body="", created_by=user.id,
                      is_fictional=False, recall_allowed=True, created_at=now, updated_at=now)
    db.add(memory)
    db.delete(s)
    db.commit()
    return memory_out(memory)


@router.post("/memory-suggestions/{sid}/dismiss", status_code=204)
def dismiss_suggestion(companion_id: str, sid: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)) -> Response:
    c = _viewer_companion(db, companion_id, user)
    db.delete(_suggestion(db, c, sid))
    db.commit()
    return Response(status_code=204)


# ---------------------------------------------------------------- greeting

@router.post("/greeting")
def greeting(
    companion_id: str,
    body: GreetingIn | None = Body(default=None),
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    client: Any = Depends(get_claude_client),
    limiter: RateLimiter = Depends(get_rate_limiter),
    settings: Settings = Depends(get_settings),
) -> dict[str, str]:
    c = _viewer_companion(db, companion_id, user)
    _require_chat_ready(db, c)
    if not limiter.allow(user.id):
        raise fail(429, "rate_limited")
    if client is None:
        raise fail(503, "chat_unavailable")
    ctx = (body or GreetingIn()).context
    inputs = build_prompt_inputs(db, c)
    messages = load_history(db, c.id) + [
        {"role": "user", "content": f"({inputs.viewer} opens the app)"},
        {"role": "system", "content": build_dynamic_context(inputs, ctx, None, extra=GREETING_INSTRUCTION)},
    ]
    try:
        text = generate_greeting(client, settings.claude_model, build_stable_block(inputs), messages)
    except ChatError:
        raise fail(503, "chat_unavailable")
    db.add(m.ChatMessage(companion_id=c.id, role="companion", text=text, kind="greeting", game=None))
    db.commit()
    return {"text": text}


# ---------------------------------------------------------------- chat (SSE)

def sse(event: str, data: dict[str, Any]) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def _sse_response(body: Iterator[str]) -> StreamingResponse:
    return StreamingResponse(
        body, media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}
    )


@router.post("/chat")
def chat(
    companion_id: str,
    body: ChatIn,
    request: Request,
    user: m.User = Depends(current_user),
    db: Session = Depends(get_db),
    client: Any = Depends(get_claude_client),
    limiter: RateLimiter = Depends(get_rate_limiter),
    settings: Settings = Depends(get_settings),
) -> StreamingResponse:
    c = _viewer_companion(db, companion_id, user)
    _require_chat_ready(db, c)
    if body.mode == "game" and body.game is None:
        raise fail(422, "game_required")
    game = body.game if body.mode == "game" else None
    kind = "game" if game else "chat"
    session_factory: sessionmaker = request.app.state.session_factory
    cid = c.id

    if not limiter.allow(user.id):
        return _sse_response(iter([sse("error", {"detail": "rate_limited"})]))
    if client is None:
        return _sse_response(iter([sse("error", {"detail": "chat_unavailable"})]))

    inputs = build_prompt_inputs(db, c)
    stable_block = build_stable_block(inputs)
    history = load_history(db, cid)
    user_message = m.ChatMessage(companion_id=cid, role="user", text=body.text, kind=kind, game=game)
    db.add(user_message)
    db.commit()
    user_message_id = user_message.id
    messages = history + [
        {"role": "user", "content": body.text},
        {"role": "system", "content": build_dynamic_context(inputs, body.context, game)},
    ]

    def on_suggestion(text: str) -> dict[str, Any]:
        with session_factory() as s:
            row = m.MemorySuggestion(companion_id=cid, text=text)
            s.add(row)
            s.commit()
            return suggestion_out(row)

    def events() -> Iterator[str]:
        for name, data in stream_reply(client, settings.claude_model, stable_block, messages, on_suggestion):
            if name == "final":
                with session_factory() as s:
                    reply = m.ChatMessage(companion_id=cid, role="companion", text=data["text"], kind=kind, game=game)
                    s.add(reply)
                    s.commit()
                    reply_id = reply.id
                yield sse("done", {"user_message_id": user_message_id, "message_id": reply_id})
                return
            yield sse(name, data)

    return _sse_response(events())
