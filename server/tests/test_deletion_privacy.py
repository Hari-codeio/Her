from __future__ import annotations

from app import models as m

from .conftest import APPEARANCE, JPEG, PERSONA, PNG, WARDROBE_ITEM, Api, blob_files
from .fake_claude import text_turn, tool_turn

VOICE = {"file": ("v.m4a", b"voice-bytes", "audio/mp4")}


def _populate(arjun: Api, maya: Api, fake_claude) -> dict[str, str]:
    """Both companions active with photos, voice, wardrobe, memories (with photos), occasions,
    chat history and a pending memory suggestion."""
    arjun_cid, maya_cid = arjun.of_me()["id"], maya.of_me()["id"]
    for user, cid in ((arjun, arjun_cid), (maya, maya_cid)):
        user.consent(cid, likeness=True, voice=True, personality=True)
        user.put(f"/v1/companions/{cid}/appearance", json=APPEARANCE)
        user.put(f"/v1/companions/{cid}/persona", json=PERSONA)
        user.upload_photo(cid)
        user.post(f"/v1/companions/{cid}/voice/samples", files=VOICE)
        user.post(f"/v1/companions/{cid}/wardrobe", json=WARDROBE_ITEM)
    arjun.upload_photo(maya_cid, JPEG, "image/jpeg")  # Arjun uploads a photo of Maya's companion
    mem_a = arjun.post("/v1/memories", json={"kind": "moment", "title": "Arjun's memory"}).json()
    mem_m = maya.post("/v1/memories", json={"kind": "moment", "title": "Maya's memory"}).json()
    arjun.put(f"/v1/memories/{mem_a['id']}/photo", files={"file": ("a.png", PNG, "image/png")})
    maya.put(f"/v1/memories/{mem_m['id']}/photo", files={"file": ("m.png", PNG, "image/png")})
    arjun.post("/v1/occasions", json={"kind": "birthday", "title": "Maya's birthday", "date": "1996-03-09",
                                      "person_user_id": maya.id})
    fake_claude.turns = [tool_turn("ooh ", inputs=[{"text": "You love chai"}]), text_turn("nice"), text_turn("hey")]
    arjun.post(f"/v1/companions/{maya_cid}/chat", json={"text": "I love chai"})
    maya.post(f"/v1/companions/{arjun_cid}/chat", json={"text": "hi"})
    return {"arjun_cid": arjun_cid, "maya_cid": maya_cid, "mem_a": mem_a["id"], "mem_m": mem_m["id"]}


def _count(app, model) -> int:
    with app.state.session_factory() as s:
        return s.query(model).count()


def test_delete_couple_removes_everything_shared(pair, app, blob_dir, fake_claude):
    arjun, maya = pair()
    _populate(arjun, maya, fake_claude)
    assert len(blob_files(blob_dir)) == 2 + 2 + 1 + 2  # photos, voice samples, Arjun's photo of Maya, memory photos

    assert maya.delete("/v1/couple").status_code == 204
    for model in (m.Couple, m.Companion, m.AppearanceVersion, m.Photo, m.VoiceSample, m.WardrobeItem, m.Memory,
                  m.Occasion, m.ChatMessage, m.MemorySuggestion, m.ConsentEvent):
        assert _count(app, model) == 0, model.__name__
    assert blob_files(blob_dir) == set()
    assert _count(app, m.User) == 2  # both accounts remain, unpaired
    assert arjun.get("/v1/me").json()["couple"] is None
    assert arjun.get("/v1/couple").status_code == 404
    assert arjun.delete("/v1/couple").json() == {"detail": "not_paired"}
    # they can pair again
    code = arjun.post("/v1/couple/invite").json()["code"]
    assert maya.post("/v1/couple/join", json={"code": code}).status_code == 200


def test_delete_me_removes_account_and_dissolves_couple(pair, client, app, blob_dir, fake_claude):
    arjun, maya = pair()
    _populate(arjun, maya, fake_claude)
    other_a, other_b = pair("Zoe", "Dev")  # an unrelated couple keeps its data
    other_mem = other_a.post("/v1/memories", json={"kind": "moment", "title": "Theirs"}).json()
    other_a.put(f"/v1/memories/{other_mem['id']}/photo", files={"file": ("z.png", PNG, "image/png")})

    assert arjun.delete("/v1/me").status_code == 204
    assert arjun.get("/v1/me").status_code == 401  # token no longer valid
    with app.state.session_factory() as s:
        assert s.get(m.User, arjun.id) is None
        assert s.query(m.AuthToken).filter_by(user_id=arjun.id).count() == 0
        assert s.query(m.Invite).filter_by(inviter_id=arjun.id).count() == 0
        assert s.query(m.Companion).count() == 2  # only Zoe & Dev's
        assert s.query(m.Memory).count() == 1
        assert s.query(m.ConsentEvent).count() == 0
    assert len(blob_files(blob_dir)) == 1  # only the other couple's memory photo
    me = maya.get("/v1/me").json()
    assert me["couple"] is None and me["partner"] is None
    assert other_b.get("/v1/memories").json()[0]["title"] == "Theirs"


def test_privacy_export(pair, fake_claude):
    arjun, maya = pair()
    ids = _populate(arjun, maya, fake_claude)
    # a viewer proposal on Maya's companion, visible to both in the export
    arjun.put(f"/v1/companions/{ids['maya_cid']}/appearance", json={**APPEARANCE, "hair_style": "bun"})

    r = arjun.get("/v1/privacy/export")
    assert r.status_code == 200
    data = r.json()
    assert data["user"]["id"] == arjun.id and data["partner"]["id"] == maya.id
    assert data["couple"]["members"][0]["id"] == arjun.id
    roles = {c["role"]: c for c in data["companions"]}
    assert set(roles) == {"subject", "viewer"}
    own, theirs = roles["subject"], roles["viewer"]
    assert own["id"] == ids["arjun_cid"] and theirs["id"] == ids["maya_cid"]
    assert own["consents"] == {"likeness": True, "voice": True, "personality": True, "ai_training": False}
    assert own["persona"]["nickname_for_viewer"] == "jaan" and own["voice"] == {"status": "none", "samples": 1}
    assert [v["status"] for v in own["appearance_versions"]] == ["approved"]
    assert [v["status"] for v in theirs["appearance_versions"]] == ["approved", "pending"]
    assert len(own["photos"]) == 1 and len(theirs["photos"]) == 2
    for photo in own["photos"] + theirs["photos"]:
        assert set(photo) == {"id", "uploaded_by", "created_at", "content_type"}  # metadata only
    ledger_companions = {e["companion_id"] for e in data["consent_ledger"]}
    assert ledger_companions == {ids["arjun_cid"], ids["maya_cid"]}
    assert all(w["companion_id"] == ids["arjun_cid"] for w in data["wardrobe"]) and len(data["wardrobe"]) == 1
    assert {x["id"] for x in data["memories"]} == {ids["mem_a"], ids["mem_m"]}
    assert [o["title"] for o in data["occasions"]] == ["Maya's birthday"]
    assert [(x["role"], x["text"]) for x in data["chat_messages"]] == [("user", "I love chai"), ("companion", "ooh nice")]
    assert [s["text"] for s in data["memory_suggestions"]] == ["You love chai"]
    assert "hey" not in [x["text"] for x in data["chat_messages"]]  # Maya's chat isn't Arjun's


def test_privacy_export_respects_viewer_visibility(pair):
    arjun, maya = pair()
    maya_cid = maya.of_me()["id"]
    maya.put(f"/v1/companions/{maya_cid}/persona", json=PERSONA)  # allowed before likeness consent
    data = arjun.get("/v1/privacy/export").json()
    theirs = next(c for c in data["companions"] if c["role"] == "viewer")
    assert theirs["status"] == "awaiting_consent"
    assert theirs["persona"] is None and theirs["pending_persona"] is None and theirs["appearance_versions"] == []
    # Maya's own export includes her persona even though personality consent is off
    own = next(c for c in maya.get("/v1/privacy/export").json()["companions"] if c["role"] == "subject")
    assert own["persona"]["about"] == PERSONA["about"]
    # unpaired users still get an export
    solo = Api(arjun.client, "Solo").get("/v1/privacy/export").json()
    assert solo["couple"] is None and solo["companions"] == [] and solo["memories"] == []


def test_privacy_delete_photos(pair, app, blob_dir, fake_claude):
    arjun, maya = pair()
    ids = _populate(arjun, maya, fake_claude)
    before = blob_files(blob_dir)

    assert arjun.delete("/v1/privacy/photos").status_code == 204
    # Arjun's own companion photos, the photo he uploaded on Maya's companion, his memory's photo
    assert arjun.get(f"/v1/companions/{ids['arjun_cid']}/photos").json() == []
    remaining = maya.get(f"/v1/companions/{ids['maya_cid']}/photos").json()
    assert [p["uploaded_by"] for p in remaining] == [maya.id]
    memories = {x["id"]: x for x in maya.get("/v1/memories").json()}
    assert memories[ids["mem_a"]]["has_photo"] is False
    assert memories[ids["mem_m"]]["has_photo"] is True
    assert len(before - blob_files(blob_dir)) == 3
    assert maya.get(f"/v1/memories/{ids['mem_m']}/photo").content == PNG
    assert arjun.delete("/v1/privacy/photos").status_code == 204  # idempotent


def test_privacy_delete_voice(pair, app, blob_dir, fake_claude):
    arjun, maya = pair()
    ids = _populate(arjun, maya, fake_claude)
    assert arjun.delete("/v1/privacy/voice").status_code == 204
    assert arjun.of_me()["voice"] == {"status": "none", "samples": 0}
    assert arjun.of_me()["consents"]["voice"] is True  # consent itself is unchanged
    assert maya.of_me()["voice"]["samples"] == 1
    with app.state.session_factory() as s:
        assert s.query(m.VoiceSample).filter_by(companion_id=ids["arjun_cid"]).count() == 0
        maya_sample = s.query(m.VoiceSample).filter_by(companion_id=ids["maya_cid"]).one()
    assert maya_sample.blob_name in blob_files(blob_dir)


def test_privacy_delete_memories(pair, blob_dir, fake_claude):
    arjun, maya = pair()
    ids = _populate(arjun, maya, fake_claude)
    files_before = blob_files(blob_dir)
    assert arjun.delete("/v1/privacy/memories").status_code == 204
    assert [x["id"] for x in maya.get("/v1/memories").json()] == [ids["mem_m"]]
    assert len(files_before - blob_files(blob_dir)) == 1  # Arjun's memory photo
    assert arjun.get("/v1/occasions").json() != []  # occasions are untouched


def test_privacy_requires_auth(client):
    for method, path in (("get", "/v1/privacy/export"), ("delete", "/v1/privacy/photos"),
                         ("delete", "/v1/privacy/voice"), ("delete", "/v1/privacy/memories")):
        assert getattr(client, method)(path).status_code == 401
