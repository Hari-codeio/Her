from __future__ import annotations

from .conftest import JPEG, PNG, WARDROBE_ITEM, Api, blob_files


def _wardrobe_setup(pair):
    arjun, maya = pair()
    cid = maya.of_me()["id"]  # subject Maya, viewer Arjun
    maya.consent(cid, likeness=True)
    return arjun, maya, cid


def test_wardrobe_visibility_and_approval(pair, client):
    arjun, maya, cid = _wardrobe_setup(pair)
    base = f"/v1/companions/{cid}/wardrobe"

    own = maya.post(base, json=WARDROBE_ITEM)
    assert own.status_code == 201
    own = own.json()
    assert own["approved"] is True and own["added_by"] == maya.id and own["companion_id"] == cid
    proposal = arjun.post(base, json={**WARDROBE_ITEM, "name": "Red saree", "category": "traditional"}).json()
    assert proposal["approved"] is False

    # subject sees everything; viewer sees approved items plus their own pending ones
    assert {i["id"] for i in maya.get(base).json()} == {own["id"], proposal["id"]}
    assert {i["id"] for i in arjun.get(base).json()} == {own["id"], proposal["id"]}

    # the viewer can only edit or delete items they added
    assert arjun.patch(f"{base}/{own['id']}", json={"name": "x"}).json() == {"detail": "forbidden"}
    assert arjun.delete(f"{base}/{own['id']}").json() == {"detail": "forbidden"}

    # viewer edit of their own item keeps it pending; subject approval makes it approved
    assert arjun.post(f"{base}/{proposal['id']}/approve", json={"approved": True}).json() == {"detail": "forbidden"}
    approved = maya.post(f"{base}/{proposal['id']}/approve", json={"approved": True})
    assert approved.status_code == 200 and approved.json()["approved"] is True
    edited = arjun.patch(f"{base}/{proposal['id']}", json={"colors": ["#FF0000", "#FFD700"]})
    assert edited.status_code == 200
    assert edited.json()["approved"] is False and edited.json()["colors"] == ["#FF0000", "#FFD700"]

    # subject edits don't change approval
    assert maya.patch(f"{base}/{own['id']}", json={"warmth": 4}).json()["approved"] is True

    # approve false deletes the item
    r = maya.post(f"{base}/{proposal['id']}/approve", json={"approved": False})
    assert r.status_code == 204
    assert [i["id"] for i in arjun.get(base).json()] == [own["id"]]

    # outsiders get 404
    assert Api(client, "Zoe").get(base).status_code == 404


def test_wardrobe_viewer_does_not_see_others_pending(pair):
    arjun, maya, cid = _wardrobe_setup(pair)
    base = f"/v1/companions/{cid}/wardrobe"
    proposal = arjun.post(base, json=WARDROBE_ITEM).json()
    # a pending item the viewer didn't add is hidden from the viewer (forced pending via the DB,
    # since subject-authored items are approved on save)
    other_pending = maya.post(base, json={**WARDROBE_ITEM, "name": "Hoodie", "category": "outerwear"}).json()
    from app import models as m

    with arjun.client.app.state.session_factory() as s:
        s.query(m.WardrobeItem).filter_by(id=other_pending["id"]).update({"approved": False})
        s.commit()
    assert {i["id"] for i in arjun.get(base).json()} == {proposal["id"]}
    assert {i["id"] for i in maya.get(base).json()} == {proposal["id"], other_pending["id"]}
    assert arjun.patch(f"{base}/{other_pending['id']}", json={"name": "x"}).status_code == 404

    # viewer sees nothing once likeness is revoked (items are deleted with the likeness)
    maya.consent(cid, likeness=False)
    assert arjun.get(base).json() == []
    assert maya.get(base).json() == []


def test_wardrobe_validation(pair):
    arjun, maya, cid = _wardrobe_setup(pair)
    base = f"/v1/companions/{cid}/wardrobe"
    bad_items = [
        {**WARDROBE_ITEM, "category": "hat"},
        {**WARDROBE_ITEM, "colors": []},
        {**WARDROBE_ITEM, "colors": ["#111111"] * 4},
        {**WARDROBE_ITEM, "colors": ["blue"]},
        {**WARDROBE_ITEM, "styles": ["goth"]},
        {**WARDROBE_ITEM, "warmth": 6},
        {**WARDROBE_ITEM, "pattern": "paisley"},
    ]
    for bad in bad_items:
        assert maya.post(base, json=bad).status_code == 422, bad
    minimal = maya.post(base, json={"name": "Tee", "category": "tshirt", "colors": ["#FFFFFF"]}).json()
    assert minimal["pattern"] == "solid" and minimal["styles"] == [] and minimal["seasons"] == [] and minimal["warmth"] == 3
    item = maya.post(base, json=WARDROBE_ITEM).json()
    assert maya.patch(f"{base}/{item['id']}", json={"name": None}).status_code == 422


def test_memories_crud_and_order(pair):
    arjun, maya = pair()
    first = arjun.post("/v1/memories", json={"kind": "moment", "title": "First trip to Goa", "body": "Beach!",
                                              "happened_on": "2022-12-28", "place": "Palolem"})
    assert first.status_code == 201
    first = first.json()
    assert first["created_by"] == arjun.id and first["has_photo"] is False
    assert first["is_fictional"] is False and first["recall_allowed"] is True
    undated = maya.post("/v1/memories", json={"kind": "preference", "title": "Loves jasmine tea"}).json()
    later = maya.post("/v1/memories", json={"kind": "date", "title": "Anniversary dinner", "happened_on": "2024-02-14"}).json()

    listed = arjun.get("/v1/memories").json()
    assert [x["id"] for x in listed] == [later["id"], first["id"], undated["id"]]  # happened_on desc, nulls last

    r = maya.patch(f"/v1/memories/{first['id']}", json={"title": "Goa!", "recall_allowed": False, "place": None})
    assert r.status_code == 200
    assert r.json()["title"] == "Goa!" and r.json()["recall_allowed"] is False and r.json()["place"] is None
    assert maya.patch(f"/v1/memories/{first['id']}", json={"title": None}).status_code == 422
    assert maya.post("/v1/memories", json={"kind": "dream", "title": "x"}).status_code == 422

    assert arjun.delete(f"/v1/memories/{undated['id']}").status_code == 204
    assert arjun.get(f"/v1/memories/{undated['id']}/photo").status_code == 404
    assert len(arjun.get("/v1/memories").json()) == 2


def test_memory_photo(pair, blob_dir):
    arjun, maya = pair()
    mem = arjun.post("/v1/memories", json={"kind": "place", "title": "Café"}).json()
    assert arjun.get(f"/v1/memories/{mem['id']}/photo").status_code == 404
    r = arjun.put(f"/v1/memories/{mem['id']}/photo", files={"file": ("a.png", PNG, "image/png")})
    assert r.status_code == 200 and r.json()["has_photo"] is True
    assert maya.get(f"/v1/memories/{mem['id']}/photo").content == PNG
    # replacing the photo removes the old blob
    arjun.put(f"/v1/memories/{mem['id']}/photo", files={"file": ("a.jpg", JPEG, "image/jpeg")})
    assert len(blob_files(blob_dir)) == 1
    got = maya.get(f"/v1/memories/{mem['id']}/photo")
    assert got.content == JPEG and got.headers["content-type"] == "image/jpeg"
    bad = arjun.put(f"/v1/memories/{mem['id']}/photo", files={"file": ("a.txt", b"hello", "text/plain")})
    assert bad.status_code == 422
    assert arjun.delete(f"/v1/memories/{mem['id']}").status_code == 204
    assert blob_files(blob_dir) == set()


def test_occasions_crud(pair):
    arjun, maya = pair()
    r = arjun.post("/v1/occasions", json={"kind": "birthday", "title": "Maya's birthday", "date": "1996-03-09",
                                          "person_user_id": maya.id})
    assert r.status_code == 201
    occ = r.json()
    assert occ["recurs_yearly"] is True and occ["companion_may_initiate"] is True and occ["created_by"] == arjun.id
    assert set(occ) == {"id", "couple_id", "kind", "title", "date", "recurs_yearly", "person_user_id",
                        "companion_may_initiate", "created_by"}
    assert maya.get("/v1/occasions").json() == [occ]
    r = maya.patch(f"/v1/occasions/{occ['id']}", json={"companion_may_initiate": False, "title": "Bday"})
    assert r.json()["companion_may_initiate"] is False and r.json()["title"] == "Bday"
    assert maya.post("/v1/occasions", json={"kind": "birthday", "title": "x", "date": "2020-01-01",
                                            "person_user_id": "someone-else"}).status_code == 422
    assert maya.delete(f"/v1/occasions/{occ['id']}").status_code == 204
    assert maya.get("/v1/occasions").json() == []


def test_memories_and_occasions_are_couple_isolated(pair, client):
    arjun, maya = pair()
    zoe, dev = pair("Zoe", "Dev")
    mem = arjun.post("/v1/memories", json={"kind": "moment", "title": "Ours"}).json()
    occ = arjun.post("/v1/occasions", json={"kind": "custom", "title": "Ours", "date": "2027-01-01"}).json()
    assert zoe.get("/v1/memories").json() == [] and zoe.get("/v1/occasions").json() == []
    assert zoe.patch(f"/v1/memories/{mem['id']}", json={"title": "mine"}).status_code == 404
    assert zoe.delete(f"/v1/memories/{mem['id']}").status_code == 404
    assert zoe.get(f"/v1/memories/{mem['id']}/photo").status_code == 404
    assert dev.patch(f"/v1/occasions/{occ['id']}", json={"title": "mine"}).status_code == 404
    assert dev.delete(f"/v1/occasions/{occ['id']}").status_code == 404
    assert Api(client, "Solo").get("/v1/occasions").json() == {"detail": "not_paired"}
    assert arjun.get("/v1/memories").json()[0]["title"] == "Ours"
