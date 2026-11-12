from __future__ import annotations

import re
from datetime import timedelta

from app import models as m
from app.ai.claude import claude_configured
from app.config import CONSENT_STATEMENT_VERSION, CONSENT_STATEMENTS
from app.routers.couple import CODE_ALPHABET

from .conftest import Api


def test_healthz_and_config(client):
    assert client.get("/healthz").json() == {"ok": True}
    cfg = client.get("/v1/config").json()
    assert cfg["chat_available"] is True  # fake client injected
    assert cfg["likeness_provider"] is False and cfg["voice_provider"] is False
    assert cfg["consent_statement_version"] == "2026-11-01" == CONSENT_STATEMENT_VERSION
    assert cfg["consent_statements"] == CONSENT_STATEMENTS
    assert set(cfg["consent_statements"]) == {"likeness", "voice", "personality", "ai_training"}
    assert claude_configured(None) is False


def test_register_and_me(client, app):
    r = client.post("/v1/auth/register", json={"display_name": "  Arjun  "})
    assert r.status_code == 201
    body = r.json()
    assert body["user"]["display_name"] == "Arjun"
    assert re.fullmatch(r"[0-9a-f]{32}", body["user"]["id"])
    assert body["user"]["created_at"].endswith("Z")
    token = body["token"]

    me = client.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert me.status_code == 200
    assert me.json() == {"user": body["user"], "couple": None, "partner": None}

    # only a hash of the token is stored
    with app.state.session_factory() as s:
        stored = s.query(m.AuthToken).one()
        assert stored.token_hash != token and len(stored.token_hash) == 64


def test_register_validation(client):
    assert client.post("/v1/auth/register", json={"display_name": ""}).status_code == 422
    assert client.post("/v1/auth/register", json={"display_name": "   "}).status_code == 422
    assert client.post("/v1/auth/register", json={"display_name": "x" * 41}).status_code == 422


def test_auth_required(client):
    assert client.get("/v1/me").status_code == 401
    assert client.get("/v1/me").json() == {"detail": "unauthorized"}
    assert client.get("/v1/me", headers={"Authorization": "Bearer nope"}).status_code == 401
    assert client.get("/v1/me", headers={"Authorization": "Basic abc"}).status_code == 401


def test_patch_me(client):
    a = Api(client, "Arjun")
    r = a.patch("/v1/me", json={"display_name": "Arjun K"})
    assert r.status_code == 200 and r.json()["display_name"] == "Arjun K"
    assert a.patch("/v1/me", json={"display_name": None}).status_code == 422
    assert a.patch("/v1/me", json={}).json()["display_name"] == "Arjun K"


def test_invite_and_join_creates_couple_and_companions(client):
    a, b = Api(client, "Arjun"), Api(client, "Maya")
    inv = a.post("/v1/couple/invite")
    assert inv.status_code == 200
    code = inv.json()["code"]
    assert re.fullmatch(rf"[{CODE_ALPHABET}]{{4}}-[{CODE_ALPHABET}]{{4}}", code)
    assert not set(code) & set("01OI")
    assert inv.json()["expires_at"].endswith("Z")

    r = b.post("/v1/couple/join", json={"code": code.lower()})  # case-insensitive
    assert r.status_code == 200, r.text
    couple = r.json()
    assert [u["id"] for u in couple["members"]] == [a.id, b.id]
    assert couple["anniversary_date"] is None

    me = a.get("/v1/me").json()
    assert me["couple"]["id"] == couple["id"] and me["partner"]["id"] == b.id
    assert b.get("/v1/couple").json()["id"] == couple["id"]

    # one companion in each direction, both awaiting consent
    a_mine, a_of_me = a.mine(), a.of_me()
    assert a_mine["subject"]["id"] == b.id and a_mine["viewer"]["id"] == a.id
    assert a_of_me["subject"]["id"] == a.id and a_of_me["viewer"]["id"] == b.id
    assert a_mine["name"] == "Maya"
    for comp in (a_mine, a_of_me):
        assert comp["status"] == "awaiting_consent"
        assert comp["consents"] == {"likeness": False, "voice": False, "personality": False, "ai_training": False}
        assert comp["voice"] == {"status": "none", "samples": 0} and comp["photo_count"] == 0
    assert b.mine()["id"] == a_of_me["id"]
    assert a.get(f"/v1/companions/{a_mine['id']}").status_code == 200


def test_join_errors(client, app):
    a, b, c = Api(client, "Arjun"), Api(client, "Maya"), Api(client, "Zoe")
    code = a.post("/v1/couple/invite").json()["code"]

    assert a.post("/v1/couple/join", json={"code": code}).json() == {"detail": "own_code"}
    assert a.post("/v1/couple/join", json={"code": code}).status_code == 409
    r = b.post("/v1/couple/join", json={"code": "ZZZZ-ZZZZ"})
    assert r.status_code == 404 and r.json() == {"detail": "invalid_code"}

    assert b.post("/v1/couple/join", json={"code": code}).status_code == 200
    # used code
    r = c.post("/v1/couple/join", json={"code": code})
    assert r.status_code == 404 and r.json()["detail"] == "invalid_code"
    # already paired: can't invite or join
    assert a.post("/v1/couple/invite").json() == {"detail": "already_paired"}
    assert a.post("/v1/couple/invite").status_code == 409
    code_c = c.post("/v1/couple/invite").json()["code"]
    r = b.post("/v1/couple/join", json={"code": code_c})
    assert r.status_code == 409 and r.json()["detail"] == "already_paired"

    # expired code
    d = Api(client, "Dev")
    with app.state.session_factory() as s:
        inv = s.get(m.Invite, code_c)
        inv.expires_at = inv.expires_at - timedelta(hours=25)
        s.commit()
    r = d.post("/v1/couple/join", json={"code": code_c})
    assert r.status_code == 404 and r.json()["detail"] == "invalid_code"


def test_new_invite_replaces_old_one(client):
    a, b = Api(client, "Arjun"), Api(client, "Maya")
    first = a.post("/v1/couple/invite").json()["code"]
    second = a.post("/v1/couple/invite").json()["code"]
    assert b.post("/v1/couple/join", json={"code": first}).status_code == 404
    assert b.post("/v1/couple/join", json={"code": second}).status_code == 200


def test_unpaired_lookups(client):
    a = Api(client, "Arjun")
    assert a.get("/v1/couple").json() == {"detail": "not_paired"}
    assert a.get("/v1/companions/mine").status_code == 404
    assert a.get("/v1/companions/of-me").json()["detail"] == "not_paired"
    assert a.get("/v1/memories").json()["detail"] == "not_paired"
    assert a.get("/v1/companions/abc").json()["detail"] == "not_found"


def test_patch_couple_anniversary(pair):
    a, b = pair()
    r = a.patch("/v1/couple", json={"anniversary_date": "2021-02-14"})
    assert r.status_code == 200 and r.json()["anniversary_date"] == "2021-02-14"
    assert b.get("/v1/couple").json()["anniversary_date"] == "2021-02-14"
    assert a.patch("/v1/couple", json={"anniversary_date": None}).json()["anniversary_date"] is None
    assert a.patch("/v1/couple", json={"anniversary_date": "not-a-date"}).status_code == 422


def test_companion_access_is_limited_to_the_couple(client, pair):
    a, b = pair()
    outsider = Api(client, "Zoe")
    cid = a.mine()["id"]
    assert outsider.get(f"/v1/companions/{cid}").status_code == 404
    assert outsider.get(f"/v1/companions/{cid}/consent-history").status_code == 404


def test_rename_companion_subject_only(pair):
    a, b = pair()
    cid = b.of_me()["id"]
    assert a.patch(f"/v1/companions/{cid}", json={"name": "Mayu"}).json() == {"detail": "forbidden"}
    r = b.patch(f"/v1/companions/{cid}", json={"name": "Mayu"})
    assert r.status_code == 200 and r.json()["name"] == "Mayu"
    assert b.patch(f"/v1/companions/{cid}", json={"name": None}).json()["name"] == "Maya"
