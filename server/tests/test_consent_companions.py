from __future__ import annotations

from app import models as m
from app.config import CONSENT_STATEMENTS

from .conftest import APPEARANCE, JPEG, PERSONA, PNG, WARDROBE_ITEM, blob_files


def test_nothing_is_accepted_before_likeness_consent(pair):
    arjun, maya = pair()
    cid = maya.of_me()["id"]  # subject Maya, viewer Arjun
    for user in (arjun, maya):
        r = user.upload_photo(cid)
        assert r.status_code == 403 and r.json() == {"detail": "likeness_consent_required"}
        r = user.put(f"/v1/companions/{cid}/appearance", json=APPEARANCE)
        assert r.status_code == 403 and r.json()["detail"] == "likeness_consent_required"
        r = user.post(f"/v1/companions/{cid}/wardrobe", json=WARDROBE_ITEM)
        assert r.status_code == 403 and r.json()["detail"] == "likeness_consent_required"
        r = user.post(f"/v1/companions/{cid}/likeness/generate")
        assert r.status_code == 403 and r.json()["detail"] == "likeness_consent_required"
    r = arjun.post(f"/v1/companions/{cid}/chat", json={"text": "hi"})
    assert r.status_code == 403 and r.json()["detail"] == "likeness_consent_required"
    r = arjun.post(f"/v1/companions/{cid}/greeting", json={"context": {}})
    assert r.status_code == 403 and r.json()["detail"] == "likeness_consent_required"


def test_only_subject_can_change_consent(pair):
    arjun, maya = pair()
    cid = maya.of_me()["id"]
    r = arjun.consent(cid, likeness=True)
    assert r.status_code == 403 and r.json() == {"detail": "forbidden"}


def test_status_derivation(pair):
    arjun, maya = pair()
    cid = maya.of_me()["id"]
    assert maya.of_me()["status"] == "awaiting_consent"
    r = maya.consent(cid, likeness=True)
    assert r.status_code == 200 and r.json()["status"] == "draft"
    r = maya.put(f"/v1/companions/{cid}/appearance", json=APPEARANCE)
    assert r.json()["status"] == "active" and r.json()["appearance"] == APPEARANCE
    assert arjun.mine()["status"] == "active"
    r = maya.consent(cid, likeness=False)
    assert r.json()["status"] == "revoked"
    assert r.json()["appearance"] is None and r.json()["pending_appearance"] is None
    r = maya.consent(cid, likeness=True)
    assert r.json()["status"] == "draft"  # appearance versions were deleted on revoke


def test_consent_ledger_and_stale_statement(pair):
    arjun, maya = pair()
    cid = maya.of_me()["id"]
    r = maya.put(f"/v1/companions/{cid}/consent", json={"likeness": True, "statement_version": "2025-01-01"})
    assert r.status_code == 409 and r.json() == {"detail": "stale_statement"}
    assert maya.put(f"/v1/companions/{cid}/consent", json={"likeness": True}).status_code == 422
    assert maya.get(f"/v1/companions/{cid}/consent-history").json() == []

    r = maya.consent(cid, likeness=True, voice=True, personality=True, ai_training=False)
    assert r.json()["consents"] == {"likeness": True, "voice": True, "personality": True, "ai_training": False}
    maya.consent(cid, likeness=True, voice=False)  # likeness unchanged -> no new row for it

    history = maya.get(f"/v1/companions/{cid}/consent-history").json()
    assert [(e["scope"], e["granted"]) for e in history] == [
        ("voice", False), ("personality", True), ("voice", True), ("likeness", True)
    ]  # newest first; ai_training stayed false, so no row
    for e in history:
        assert e["statement"] == CONSENT_STATEMENTS[e["scope"]]
        assert e["statement_version"] == "2026-11-01"
        assert e["companion_id"] == cid and e["created_at"].endswith("Z")
    # the viewer can read the ledger too
    assert arjun.get(f"/v1/companions/{cid}/consent-history").json() == history


def test_viewer_sees_nothing_while_awaiting_consent(pair):
    arjun, maya = pair()
    cid = maya.of_me()["id"]
    # personality isn't gated by likeness consent, but the viewer still can't see it yet
    maya.put(f"/v1/companions/{cid}/persona", json=PERSONA)
    maya.consent(cid, personality=True)
    as_viewer = arjun.mine()
    assert as_viewer["status"] == "awaiting_consent"
    assert as_viewer["appearance"] is None and as_viewer["pending_appearance"] is None
    assert as_viewer["persona"] is None and as_viewer["pending_persona"] is None
    assert maya.of_me()["persona"]["nickname_for_viewer"] == "jaan"

    maya.consent(cid, likeness=True)
    maya.put(f"/v1/companions/{cid}/appearance", json=APPEARANCE)
    as_viewer = arjun.mine()
    assert as_viewer["appearance"] == APPEARANCE and as_viewer["persona"]["about"] == PERSONA["about"]

    maya.consent(cid, likeness=False)
    as_viewer = arjun.mine()
    assert as_viewer["status"] == "revoked"
    assert as_viewer["persona"] is None and as_viewer["appearance"] is None


def test_viewer_appearance_is_pending_until_subject_approves(pair):
    arjun, maya = pair()
    cid = maya.of_me()["id"]
    maya.consent(cid, likeness=True)

    r = arjun.put(f"/v1/companions/{cid}/appearance", json=APPEARANCE)
    assert r.status_code == 200
    comp = r.json()
    assert comp["status"] == "draft" and comp["appearance"] is None
    pending = comp["pending_appearance"]
    assert pending["status"] == "pending" and pending["authored_by"] == arjun.id and pending["decided_at"] is None

    # a newer viewer proposal replaces the older pending one
    newer = arjun.put(f"/v1/companions/{cid}/appearance", json={**APPEARANCE, "hair_style": "bun"}).json()["pending_appearance"]
    assert newer["id"] != pending["id"]
    assert maya.post(f"/v1/companions/{cid}/appearance/{pending['id']}/approve").status_code == 404

    # only the subject approves
    assert arjun.post(f"/v1/companions/{cid}/appearance/{newer['id']}/approve").json() == {"detail": "forbidden"}
    r = maya.post(f"/v1/companions/{cid}/appearance/{newer['id']}/approve")
    assert r.status_code == 200
    assert r.json()["status"] == "active" and r.json()["appearance"]["hair_style"] == "bun"
    assert r.json()["pending_appearance"] is None
    assert maya.post(f"/v1/companions/{cid}/appearance/{newer['id']}/approve").status_code == 409

    # rejection keeps the approved appearance and records the reason
    third = arjun.put(f"/v1/companions/{cid}/appearance", json={**APPEARANCE, "glasses": True}).json()["pending_appearance"]
    r = maya.post(f"/v1/companions/{cid}/appearance/{third['id']}/reject", json={"reason": "no glasses"})
    assert r.status_code == 200 and r.json()["appearance"]["glasses"] is False and r.json()["pending_appearance"] is None
    with arjun.client.app.state.session_factory() as s:
        row = s.query(m.AppearanceVersion).filter_by(id=third["id"]).one()
        assert row.status == "rejected" and row.reject_reason == "no glasses"
    # reject without a body works too
    fourth = arjun.put(f"/v1/companions/{cid}/appearance", json=APPEARANCE).json()["pending_appearance"]
    assert maya.post(f"/v1/companions/{cid}/appearance/{fourth['id']}/reject").status_code == 200


def test_appearance_validation(pair):
    arjun, maya = pair()
    cid = maya.of_me()["id"]
    maya.consent(cid, likeness=True)
    for bad in ({**APPEARANCE, "skin_tone": "red"}, {**APPEARANCE, "height": 1.5},
                {**APPEARANCE, "hair_style": "mohawk"}, {**APPEARANCE, "style_notes": "x" * 501}):
        assert maya.put(f"/v1/companions/{cid}/appearance", json=bad).status_code == 422


def test_persona_pending_and_personality_consent(pair):
    arjun, maya = pair()
    cid = maya.of_me()["id"]
    maya.consent(cid, likeness=True)
    maya.put(f"/v1/companions/{cid}/appearance", json=APPEARANCE)

    r = arjun.put(f"/v1/companions/{cid}/persona", json={"nickname_for_viewer": "babe", "interests": ["chess"]})
    comp = r.json()
    assert comp["persona"] is None
    assert comp["pending_persona"]["nickname_for_viewer"] == "babe"
    assert comp["pending_persona"]["languages"] == [] and comp["pending_persona"]["about"] is None

    assert arjun.post(f"/v1/companions/{cid}/persona/approve").json() == {"detail": "forbidden"}
    comp = maya.post(f"/v1/companions/{cid}/persona/approve").json()
    assert comp["pending_persona"] is None
    assert comp["persona"] is None  # approved, but personality consent is off
    comp = maya.consent(cid, personality=True).json()
    assert comp["persona"]["nickname_for_viewer"] == "babe"

    # subject edits are approved at once
    comp = maya.put(f"/v1/companions/{cid}/persona", json=PERSONA).json()
    assert comp["persona"]["about"] == PERSONA["about"]
    # reject a viewer proposal
    arjun.put(f"/v1/companions/{cid}/persona", json={"about": "something else"})
    comp = maya.post(f"/v1/companions/{cid}/persona/reject").json()
    assert comp["pending_persona"] is None and comp["persona"]["about"] == PERSONA["about"]
    assert maya.post(f"/v1/companions/{cid}/persona/reject").status_code == 404

    # personality revoked: persona no longer returned (and no longer sent to the model)
    assert maya.consent(cid, personality=False).json()["persona"] is None
    assert maya.put(f"/v1/companions/{cid}/persona", json={"languages": ["x"] * 21}).status_code == 422


def test_photos_upload_list_get_delete(pair, blob_dir):
    arjun, maya = pair()
    cid = maya.of_me()["id"]
    maya.consent(cid, likeness=True)

    r = maya.upload_photo(cid)
    assert r.status_code == 200, r.text
    photo = r.json()
    assert set(photo) == {"id", "uploaded_by", "created_at", "content_type"}
    assert photo["content_type"] == "image/png" and photo["uploaded_by"] == maya.id
    viewer_photo = arjun.upload_photo(cid, JPEG, "image/jpeg").json()
    assert viewer_photo["content_type"] == "image/jpeg"

    # encrypted at rest
    files = blob_files(blob_dir)
    assert len(files) == 2
    for name in files:
        assert PNG not in (blob_dir / name).read_bytes() and JPEG not in (blob_dir / name).read_bytes()

    got = arjun.get(f"/v1/companions/{cid}/photos/{photo['id']}")
    assert got.status_code == 200 and got.content == PNG and got.headers["content-type"] == "image/png"
    assert [p["id"] for p in maya.get(f"/v1/companions/{cid}/photos").json()] == [photo["id"], viewer_photo["id"]]
    assert maya.of_me()["photo_count"] == 2

    # validation
    assert maya.upload_photo(cid, b"GIF89a" + b"\x00" * 10, "image/gif").json() == {"detail": "unsupported_media_type"}
    big = PNG + b"\x00" * (10 * 1024 * 1024)
    assert maya.upload_photo(cid, big).json() == {"detail": "file_too_large"}

    # delete: only the subject or the uploader
    assert arjun.delete(f"/v1/companions/{cid}/photos/{photo['id']}").json() == {"detail": "forbidden"}
    assert arjun.delete(f"/v1/companions/{cid}/photos/{viewer_photo['id']}").status_code == 204
    assert maya.delete(f"/v1/companions/{cid}/photos/{photo['id']}").status_code == 204
    assert blob_files(blob_dir) == set()
    assert maya.get(f"/v1/companions/{cid}/photos").json() == []


def test_photo_limit(pair):
    arjun, maya = pair()
    cid = maya.of_me()["id"]
    maya.consent(cid, likeness=True)
    for _ in range(20):
        assert maya.upload_photo(cid).status_code == 200
    r = maya.upload_photo(cid)
    assert r.status_code == 409 and r.json() == {"detail": "photo_limit_reached"}


def test_revoking_likeness_deletes_photos_appearance_and_wardrobe(pair, blob_dir, app):
    arjun, maya = pair()
    cid = maya.of_me()["id"]
    maya.consent(cid, likeness=True, voice=True)
    maya.put(f"/v1/companions/{cid}/appearance", json=APPEARANCE)
    arjun.put(f"/v1/companions/{cid}/appearance", json={**APPEARANCE, "hair_style": "bun"})
    maya.upload_photo(cid)
    arjun.upload_photo(cid, JPEG, "image/jpeg")
    maya.post(f"/v1/companions/{cid}/wardrobe", json=WARDROBE_ITEM)
    maya.post(f"/v1/companions/{cid}/voice/samples", files={"file": ("v.m4a", b"voice-bytes", "audio/mp4")})
    assert len(blob_files(blob_dir)) == 3

    r = maya.consent(cid, likeness=False)
    comp = r.json()
    assert comp["status"] == "revoked" and comp["photo_count"] == 0
    with app.state.session_factory() as s:
        assert s.query(m.Photo).filter_by(companion_id=cid).count() == 0
        assert s.query(m.AppearanceVersion).filter_by(companion_id=cid).count() == 0
        assert s.query(m.WardrobeItem).filter_by(companion_id=cid).count() == 0
        assert s.query(m.VoiceSample).filter_by(companion_id=cid).count() == 1  # voice is separate
    assert len(blob_files(blob_dir)) == 1  # only the voice sample's blob is left
    assert maya.get(f"/v1/companions/{cid}/photos").json() == []
    # and nothing new is accepted while revoked
    assert maya.upload_photo(cid).json()["detail"] == "likeness_consent_required"


def test_voice_samples_and_revoke(pair, blob_dir, app):
    arjun, maya = pair()
    cid = maya.of_me()["id"]
    sample = {"file": ("v.m4a", b"voice-bytes", "audio/mp4")}
    r = maya.post(f"/v1/companions/{cid}/voice/samples", files=sample)
    assert r.status_code == 403 and r.json() == {"detail": "voice_consent_required"}
    maya.consent(cid, voice=True)
    assert arjun.post(f"/v1/companions/{cid}/voice/samples", files=sample).json() == {"detail": "forbidden"}
    r = maya.post(f"/v1/companions/{cid}/voice/samples", files=sample)
    assert r.status_code == 200 and r.json() == {"status": "none", "samples": 1}
    r = maya.post(f"/v1/companions/{cid}/voice/samples", files={"file": ("x.txt", b"hello", "text/plain")})
    assert r.json() == {"detail": "unsupported_media_type"}
    r = maya.post(f"/v1/companions/{cid}/voice/samples", files={"file": ("x.bin", b"hello", "application/octet-stream")})
    assert r.json() == {"detail": "unsupported_media_type"}
    # a generic part type is accepted when the bytes are a known audio container
    ogg = {"file": ("v.ogg", b"OggS" + b"\x00" * 32, "application/octet-stream")}
    assert maya.post(f"/v1/companions/{cid}/voice/samples", files=ogg).json()["samples"] == 2
    assert arjun.mine()["voice"] == {"status": "none", "samples": 2}
    assert len(blob_files(blob_dir)) == 2
    with app.state.session_factory() as s:
        assert sorted(v.content_type for v in s.query(m.VoiceSample)) == ["audio/mp4", "audio/ogg"]

    comp = maya.consent(cid, voice=False).json()
    assert comp["voice"] == {"status": "none", "samples": 0}
    assert blob_files(blob_dir) == set()
    with app.state.session_factory() as s:
        assert s.query(m.VoiceSample).count() == 0


def test_providers_not_configured(active):
    arjun, maya, cid = active()
    r = arjun.post(f"/v1/companions/{cid}/likeness/generate")
    assert r.status_code == 503 and r.json() == {"detail": "likeness_provider_not_configured"}
    assert arjun.get(f"/v1/companions/{cid}/likeness").json() == {"status": "unavailable", "assets": []}
    maya.consent(cid, voice=True)
    r = arjun.post(f"/v1/companions/{cid}/voice/speak", json={"text": "hi"})
    assert r.status_code == 503 and r.json() == {"detail": "voice_provider_not_configured"}
    assert maya.post(f"/v1/companions/{cid}/voice/speak", json={"text": "hi"}).json() == {"detail": "forbidden"}
    assert arjun.post(f"/v1/companions/{cid}/voice/speak", json={"text": "x" * 1001}).status_code == 422
