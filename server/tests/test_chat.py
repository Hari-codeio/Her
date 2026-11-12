from __future__ import annotations

from datetime import timedelta

import anthropic
import httpx2
import pytest
from fastapi.testclient import TestClient

from app.ai.claude import TOOL_RESULT_INVALID, TOOL_RESULT_OK, echo_content
from app.ai.prompts import next_occurrence
from app.util import utcnow

from .conftest import PERSONA, parse_sse
from .fake_claude import Turn, refusal_turn, text_block, text_turn, tool_turn, tool_use_block

CONTEXT = {
    "local_time": "2026-11-12T21:04:00+05:30",
    "day_part": "evening",
    "mood": "playful",
    "activity": "reading",
    "environment": "cafe",
    "outfit_summary": "lavender kurti, white jeans, silver jhumkas",
    "weather": "22°C light rain",
    "occasion_today": "Anniversary (5 years)",
    "notification_hint": "WhatsApp message from Maya",
}


def chat(user, cid, text="how's your day going?", **body):
    r = user.post(f"/v1/companions/{cid}/chat", json={"text": text, **body})
    assert r.status_code == 200, r.text
    assert r.headers["content-type"].startswith("text/event-stream")
    return parse_sse(r.text)


def _request(req_url="https://api.anthropic.com/v1/messages"):
    return httpx2.Request("POST", req_url)


def test_chat_streams_deltas_then_done_and_stores_messages(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [text_turn("Hey ", "jaan! ", "Rainy evening at the café here.")]

    events = chat(arjun, cid, context=CONTEXT)
    assert [e[0] for e in events] == ["delta", "delta", "delta", "done"]
    assert "".join(d["text"] for name, d in events if name == "delta") == "Hey jaan! Rainy evening at the café here."
    done = events[-1][1]

    stored = arjun.get(f"/v1/companions/{cid}/messages").json()
    assert [(x["role"], x["text"], x["kind"], x["game"]) for x in stored] == [
        ("user", "how's your day going?", "chat", None),
        ("companion", "Hey jaan! Rainy evening at the café here.", "chat", None),
    ]
    assert done == {"user_message_id": stored[0]["id"], "message_id": stored[1]["id"]}

    # request shape
    call = fake_claude.stream_calls[0]
    assert call["model"] == "claude-opus-5-5"
    assert call["max_tokens"] == 8000
    assert call["betas"] == ["server-side-fallback-2026-07-01"]
    assert call["fallbacks"] == "default"
    assert call["output_config"] == {"effort": "low"}
    for absent in ("thinking", "temperature", "top_p", "tool_choice"):
        assert absent not in call
    assert call["system"][0]["cache_control"] == {"type": "ephemeral"}
    assert [t["name"] for t in call["tools"]] == ["suggest_memory"]
    assert call["tools"][0]["eager_input_streaming"] is True
    msgs = call["messages"]
    assert msgs[-2] == {"role": "user", "content": "how's your day going?"}
    assert msgs[-1]["role"] == "system"
    dynamic = msgs[-1]["content"]
    for value in CONTEXT.values():
        assert value in dynamic
    stable = call["system"][0]["text"]
    assert "You are Maya, an AI companion modelled on Maya's approved personality, talking with Arjun" in stable
    for per_request in ("2026-11-12T21:04", "lavender kurti", "22°C light rain", "WhatsApp message from Maya"):
        assert per_request not in stable  # nothing per-request in the cached block


def test_stable_block_is_identical_across_requests(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [text_turn("one"), text_turn("two")]
    chat(arjun, cid, "hi", context=CONTEXT)
    chat(arjun, cid, "hello again", context={**CONTEXT, "local_time": "2026-11-12T23:59:00+05:30", "mood": "sleepy"})
    first, second = fake_claude.stream_calls
    assert first["system"] == second["system"]
    assert first["tools"] == second["tools"]
    # history (text only) is replayed before the new turn
    assert second["messages"][:2] == [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "one"}]
    assert second["messages"][2] == {"role": "user", "content": "hello again"}
    assert "sleepy" in second["messages"][3]["content"]


def test_memory_suggestion_tool_loop_then_accept(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [
        tool_turn("Ooh, ", inputs=[{"text": "You love jasmine tea"}]),
        text_turn("noted, jasmine tea it is!"),
    ]
    events = chat(arjun, cid, "I could drink jasmine tea all day")
    assert [e[0] for e in events] == ["delta", "memory_suggestion", "delta", "done"]
    suggestion = events[1][1]
    assert suggestion["text"] == "You love jasmine tea" and suggestion["companion_id"] == cid
    assert set(suggestion) == {"id", "companion_id", "text", "created_at"}

    # second request carries the assistant content unchanged plus one user message of tool results
    second = fake_claude.stream_calls[1]["messages"]
    assert second[-2]["role"] == "assistant"
    assert [b.type for b in second[-2]["content"]] == ["text", "tool_use"]
    assert second[-1] == {"role": "user", "content": [
        {"type": "tool_result", "tool_use_id": "toolu_0", "content": TOOL_RESULT_OK}
    ]}
    # the trailing context system message sits right after the user turn
    assert second[-3]["role"] == "system"

    stored = arjun.get(f"/v1/companions/{cid}/messages").json()
    assert stored[-1]["text"] == "Ooh, noted, jasmine tea it is!"

    listed = arjun.get(f"/v1/companions/{cid}/memory-suggestions").json()
    assert [s["id"] for s in listed] == [suggestion["id"]]
    assert maya.get(f"/v1/companions/{cid}/memory-suggestions").json() == {"detail": "forbidden"}
    r = arjun.post(f"/v1/companions/{cid}/memory-suggestions/{suggestion['id']}/accept")
    assert r.status_code == 201
    memory = r.json()
    assert memory["kind"] == "preference" and memory["title"] == "You love jasmine tea"
    assert memory["created_by"] == arjun.id and memory["recall_allowed"] is True
    assert arjun.get(f"/v1/companions/{cid}/memory-suggestions").json() == []
    assert [x["id"] for x in maya.get("/v1/memories").json()] == [memory["id"]]
    assert arjun.post(f"/v1/companions/{cid}/memory-suggestions/{suggestion['id']}/accept").status_code == 404


def test_dismiss_suggestion(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [tool_turn(inputs=[{"text": "You hate mornings"}]), text_turn("ha")]
    sid = [d for n, d in chat(arjun, cid) if n == "memory_suggestion"][0]["id"]
    assert arjun.post(f"/v1/companions/{cid}/memory-suggestions/{sid}/dismiss").status_code == 204
    assert arjun.get(f"/v1/companions/{cid}/memory-suggestions").json() == []
    assert arjun.get("/v1/memories").json() == []


def test_invalid_tool_input_is_reported_back_not_saved(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [
        tool_turn(inputs=[{"text": ""}, {"text": "x" * 201}, {"note": "wrong key"}, "not a dict"]),
        text_turn("okay"),
    ]
    events = chat(arjun, cid)
    assert [e[0] for e in events] == ["delta", "done"]
    results = fake_claude.stream_calls[1]["messages"][-1]["content"]
    assert len(results) == 4
    assert all(r["is_error"] is True and r["content"] == TOOL_RESULT_INVALID for r in results)
    assert arjun.get(f"/v1/companions/{cid}/memory-suggestions").json() == []


def test_refusal_emits_declined_and_discards_reply(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [refusal_turn("I'd rather")]
    events = chat(arjun, cid, "say something you shouldn't")
    assert [e[0] for e in events] == ["delta", "error"]
    assert events[-1][1] == {"detail": "declined"}
    stored = arjun.get(f"/v1/companions/{cid}/messages").json()
    assert [x["role"] for x in stored] == ["user"]  # user message persisted before streaming


def test_unparseable_tool_json_is_retried(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [Turn(raise_value_error=True), Turn(raise_value_error=True), text_turn("there we go")]
    events = chat(arjun, cid)
    assert [e[0] for e in events] == ["delta", "done"]
    assert len(fake_claude.stream_calls) == 3


def test_unparseable_tool_json_gives_up_after_two_retries(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [Turn(raise_value_error=True)] * 3
    events = chat(arjun, cid)
    assert events == [("error", {"detail": "chat_unavailable"})]
    assert len(fake_claude.stream_calls) == 3


def test_tool_loop_is_capped_at_three_calls(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [tool_turn(f"{i} ", inputs=[{"text": f"fact {i}"}]) for i in range(4)]
    events = chat(arjun, cid)
    assert len(fake_claude.stream_calls) == 3
    assert [e[0] for e in events].count("memory_suggestion") == 3
    assert events[-1][0] == "done"


def test_reply_without_any_text_is_an_error_not_an_empty_message(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [tool_turn(inputs=[{"text": f"fact {i}"}]) for i in range(3)]
    events = chat(arjun, cid)
    assert [e[0] for e in events] == ["memory_suggestion"] * 3 + ["error"]
    assert events[-1][1] == {"detail": "chat_unavailable"}
    assert [x["role"] for x in arjun.get(f"/v1/companions/{cid}/messages").json()] == ["user"]


def test_max_tokens_with_pending_tool_use_does_not_run_tool(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [Turn(chunks=["partial"], stop_reason="max_tokens",
                              tool_uses=[tool_use_block("toolu_x", {"text": "You li"})])]
    events = chat(arjun, cid)
    assert [e[0] for e in events] == ["delta", "done"]
    assert len(fake_claude.stream_calls) == 1
    assert arjun.get(f"/v1/companions/{cid}/memory-suggestions").json() == []


@pytest.mark.parametrize(
    ("exc", "code"),
    [
        (lambda: anthropic.RateLimitError("slow down", response=httpx2.Response(429, request=_request()), body=None), "rate_limited"),
        (lambda: anthropic.AuthenticationError("bad key", response=httpx2.Response(401, request=_request()), body=None), "chat_unavailable"),
        (lambda: anthropic.PermissionDeniedError("no", response=httpx2.Response(403, request=_request()), body=None), "chat_unavailable"),
        (lambda: anthropic.InternalServerError("boom", response=httpx2.Response(500, request=_request()), body=None), "chat_unavailable"),
        (lambda: anthropic.APIConnectionError(request=_request()), "chat_unavailable"),
        (lambda: TypeError("Could not resolve authentication method"), "chat_unavailable"),
    ],
)
def test_api_errors_map_to_sse_error(active, fake_claude, exc, code):
    arjun, maya, cid = active()
    fake_claude.turns = [Turn(exception=exc())]
    assert chat(arjun, cid) == [("error", {"detail": code})]


def test_chat_preconditions(pair, active, fake_claude):
    arjun, maya, cid = active()
    r = maya.post(f"/v1/companions/{cid}/chat", json={"text": "hi"})
    assert r.status_code == 403 and r.json() == {"detail": "forbidden"}
    assert arjun.post(f"/v1/companions/{cid}/chat", json={"text": ""}).status_code == 422
    assert arjun.post(f"/v1/companions/{cid}/chat", json={"text": "x" * 4001}).status_code == 422
    r = arjun.post(f"/v1/companions/{cid}/chat", json={"text": "hi", "mode": "game"})
    assert r.status_code == 422 and r.json() == {"detail": "game_required"}
    r = arjun.post(f"/v1/companions/{cid}/chat", json={"text": "hi", "context": {"notification_hint": "x" * 121}})
    assert r.status_code == 422

    # draft companion (likeness granted, no approved appearance)
    zoe, dev = pair("Zoe", "Dev")
    dev_cid = dev.of_me()["id"]
    dev.consent(dev_cid, likeness=True)
    r = zoe.post(f"/v1/companions/{dev_cid}/chat", json={"text": "hi"})
    assert r.status_code == 409 and r.json() == {"detail": "companion_not_active"}
    assert fake_claude.stream_calls == []


def test_game_mode(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [text_turn("Truth or dare?")]
    events = chat(arjun, cid, "let's play", mode="game", game="truth_or_dare")
    assert events[-1][0] == "done"
    assert "Truth or Dare" in fake_claude.stream_calls[0]["messages"][-1]["content"]
    stored = arjun.get(f"/v1/companions/{cid}/messages").json()
    assert {(x["kind"], x["game"]) for x in stored} == {("game", "truth_or_dare")}


def test_prompt_uses_persona_memories_and_occasions(active, fake_claude):
    arjun, maya, cid = active()
    maya.put(f"/v1/companions/{cid}/persona", json=PERSONA)
    arjun.post("/v1/memories", json={"kind": "moment", "title": "First trip to Goa", "body": "Sunsets.",
                                     "happened_on": "2022-12-28", "place": "Palolem"})
    arjun.post("/v1/memories", json={"kind": "moment", "title": "Secret thing", "recall_allowed": False})
    arjun.post("/v1/memories", json={"kind": "moment", "title": "Dragon island", "is_fictional": True})
    soon = (utcnow().date() + timedelta(days=8)).isoformat()
    far = (utcnow().date() + timedelta(days=200)).isoformat()
    arjun.post("/v1/occasions", json={"kind": "custom", "title": "Concert night", "date": soon, "recurs_yearly": False})
    arjun.post("/v1/occasions", json={"kind": "custom", "title": "Far away", "date": far, "recurs_yearly": False})
    fake_claude.turns = [text_turn("a"), text_turn("b")]

    chat(arjun, cid)
    stable = fake_claude.stream_calls[0]["system"][0]["text"]
    assert "jaan" not in stable  # personality consent is off: persona not sent
    assert "neutral, warm, friendly style" in stable
    assert "First trip to Goa (2022-12-28 at Palolem): Sunsets." in stable
    assert "Secret thing" not in stable
    assert "[fictional story you made up together] Dragon island" in stable
    assert "Concert night" in stable and "Far away" not in stable

    maya.consent(cid, personality=True)
    chat(arjun, cid)
    stable = fake_claude.stream_calls[1]["system"][0]["text"]
    assert "Nickname for Arjun: jaan" in stable and "Boundaries (always respect these): don't talk about my job" in stable


def test_next_occurrence():
    from datetime import date

    today = date(2026, 11, 12)
    assert next_occurrence(date(1996, 3, 9), True, today) == date(2027, 3, 9)
    assert next_occurrence(date(1996, 11, 12), True, today) == date(2026, 11, 12)
    assert next_occurrence(date(2000, 2, 29), True, today) == date(2027, 2, 28)
    assert next_occurrence(date(2026, 1, 1), False, today) is None


def test_echo_content_after_fallback():
    early_tool = tool_use_block("t1", {"text": "x"})  # from the declined model, before the boundary
    content = [text_block("partial"), early_tool, {"type": "fallback"}, text_block("rest"), tool_use_block("t2", {"text": "y"})]
    echoed = echo_content(content)
    assert early_tool not in echoed and len(echoed) == 4
    plain = [text_block("a"), tool_use_block("t3", {"text": "z"})]
    assert echo_content(plain) == plain


def test_history_pagination_and_clear(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.turns = [text_turn(f"reply {i}") for i in range(3)]
    for i in range(3):
        chat(arjun, cid, f"msg {i}")
    all_msgs = arjun.get(f"/v1/companions/{cid}/messages").json()
    assert [x["text"] for x in all_msgs] == ["msg 0", "reply 0", "msg 1", "reply 1", "msg 2", "reply 2"]
    page = arjun.get(f"/v1/companions/{cid}/messages", params={"limit": 2, "before": all_msgs[4]["id"]}).json()
    assert [x["text"] for x in page] == ["msg 1", "reply 1"]
    assert arjun.get(f"/v1/companions/{cid}/messages", params={"before": "nope"}).status_code == 404
    assert arjun.get(f"/v1/companions/{cid}/messages", params={"limit": 0}).status_code == 422
    assert maya.get(f"/v1/companions/{cid}/messages").json() == {"detail": "forbidden"}
    assert arjun.delete(f"/v1/companions/{cid}/messages").status_code == 204
    assert arjun.get(f"/v1/companions/{cid}/messages").json() == []


def test_greeting(active, fake_claude):
    arjun, maya, cid = active()
    r = arjun.post(f"/v1/companions/{cid}/greeting", json={"context": CONTEXT})
    assert r.status_code == 200 and r.json() == {"text": "Hey you! Cozy evening at the café."}
    stored = arjun.get(f"/v1/companions/{cid}/messages").json()
    assert [(x["role"], x["kind"]) for x in stored] == [("companion", "greeting")]

    call = fake_claude.create_calls[0]
    assert call["max_tokens"] == 2000 and call["output_config"] == {"effort": "low"}
    assert call["fallbacks"] == "default" and call["betas"] == ["server-side-fallback-2026-07-01"]
    assert call["tool_choice"] == {"type": "none"} and "thinking" not in call
    assert call["messages"][-1]["role"] == "system" and "greeting" in call["messages"][-1]["content"]
    assert "lavender kurti" in call["messages"][-1]["content"]

    # a stored greeting (assistant) at the start of history is dropped before the first user turn
    fake_claude.turns = [text_turn("hi!")]
    chat(arjun, cid, "hello")
    assert fake_claude.stream_calls[0]["messages"][0] == {"role": "user", "content": "hello"}

    assert maya.post(f"/v1/companions/{cid}/greeting", json={}).json() == {"detail": "forbidden"}
    assert arjun.post(f"/v1/companions/{cid}/greeting").status_code == 200  # body optional


def test_greeting_failures(active, fake_claude):
    arjun, maya, cid = active()
    fake_claude.greeting = Turn(exception=anthropic.APIConnectionError(request=_request()))
    r = arjun.post(f"/v1/companions/{cid}/greeting", json={"context": {}})
    assert r.status_code == 503 and r.json() == {"detail": "chat_unavailable"}
    fake_claude.greeting = refusal_turn()
    assert arjun.post(f"/v1/companions/{cid}/greeting", json={"context": {}}).status_code == 503
    assert arjun.get(f"/v1/companions/{cid}/messages").json() == []


def test_rate_limit(make_app, fake_claude):
    from .conftest import APPEARANCE, Api

    client = TestClient(make_app(chat_rate_limit_per_minute=2))
    arjun, maya = Api(client, "Arjun"), Api(client, "Maya")
    maya.post("/v1/couple/join", json={"code": arjun.post("/v1/couple/invite").json()["code"]})
    cid = maya.of_me()["id"]
    maya.consent(cid, likeness=True)
    maya.put(f"/v1/companions/{cid}/appearance", json=APPEARANCE)

    fake_claude.turns = [text_turn("one")]
    assert chat(arjun, cid)[-1][0] == "done"
    assert arjun.post(f"/v1/companions/{cid}/greeting", json={}).status_code == 200
    assert chat(arjun, cid) == [("error", {"detail": "rate_limited"})]
    r = arjun.post(f"/v1/companions/{cid}/greeting", json={})
    assert r.status_code == 429 and r.json() == {"detail": "rate_limited"}
    # a rate-limited chat isn't stored
    assert [x["text"] for x in arjun.get(f"/v1/companions/{cid}/messages").json()].count("how's your day going?") == 1
