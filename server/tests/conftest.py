"""Test fixtures: every test gets a fresh app on a temp SQLite DB and temp blob dir, with a fake
Claude client injected through the `get_claude_client` dependency."""
from __future__ import annotations

import dataclasses
import json
import os
import tempfile
from pathlib import Path
from typing import Any

# Point the module-level app (created on import of app.main) at a throwaway directory so tests
# never touch server/data. Must run before anything imports app.main.
os.environ["HER_DATA_DIR"] = tempfile.mkdtemp(prefix="her-test-import-")
os.environ.pop("HER_DATABASE_URL", None)
os.environ.pop("HER_STORAGE_KEY", None)

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.ai.claude import get_claude_client  # noqa: E402
from app.config import CONSENT_STATEMENT_VERSION, Settings  # noqa: E402
from app.main import create_app  # noqa: E402

from .fake_claude import FakeClaude  # noqa: E402

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 64

APPEARANCE = {
    "presentation": "feminine",
    "skin_tone": "#C68E6A",
    "hair_style": "long_wavy",
    "hair_color": "#2B1B12",
    "eye_color": "#3B2416",
    "face_shape": "oval",
    "height": 0.5,
    "build": 0.4,
    "facial_hair": "none",
    "glasses": False,
    "style_notes": "loves pastel kurtis and white sneakers",
}

PERSONA = {
    "nickname_for_viewer": "jaan",
    "speaking_style": "playful, teasing, short texts",
    "languages": ["English", "Hindi"],
    "interests": ["indie music", "baking"],
    "boundaries": ["don't talk about my job"],
    "about": "Grew up in Pune, architect.",
}

WARDROBE_ITEM = {
    "name": "Lavender kurti",
    "category": "top",
    "colors": ["#B9A6E0"],
    "pattern": "solid",
    "styles": ["casual", "traditional"],
    "seasons": ["spring", "summer"],
    "warmth": 2,
}


class Api:
    """A registered user talking to the test server."""

    def __init__(self, client: TestClient, name: str):
        self.client = client
        r = client.post("/v1/auth/register", json={"display_name": name})
        assert r.status_code == 201, r.text
        self.token = r.json()["token"]
        self.user = r.json()["user"]
        self.id = self.user["id"]
        self.name = name
        self.headers = {"Authorization": f"Bearer {self.token}"}

    def get(self, path: str, **kw: Any):
        return self.client.get(path, headers=self.headers, **kw)

    def post(self, path: str, **kw: Any):
        return self.client.post(path, headers=self.headers, **kw)

    def put(self, path: str, **kw: Any):
        return self.client.put(path, headers=self.headers, **kw)

    def patch(self, path: str, **kw: Any):
        return self.client.patch(path, headers=self.headers, **kw)

    def delete(self, path: str, **kw: Any):
        return self.client.delete(path, headers=self.headers, **kw)

    # -- shortcuts
    def of_me(self) -> dict[str, Any]:
        r = self.get("/v1/companions/of-me")
        assert r.status_code == 200, r.text
        return r.json()

    def mine(self) -> dict[str, Any]:
        r = self.get("/v1/companions/mine")
        assert r.status_code == 200, r.text
        return r.json()

    def consent(self, companion_id: str, **values: bool):
        return self.put(f"/v1/companions/{companion_id}/consent",
                        json={**values, "statement_version": CONSENT_STATEMENT_VERSION})

    def upload_photo(self, companion_id: str, data: bytes = PNG, content_type: str = "image/png"):
        return self.post(f"/v1/companions/{companion_id}/photos", files={"file": ("p.png", data, content_type)})


@pytest.fixture
def fake_claude() -> FakeClaude:
    return FakeClaude()


@pytest.fixture
def make_app(tmp_path: Path, fake_claude: FakeClaude):
    created = []

    def _make(**overrides: Any):
        settings = Settings(
            data_dir=tmp_path,
            database_url=f"sqlite:///{(tmp_path / 'test.db').as_posix()}",
            blob_dir=tmp_path / "blobs",
            storage_key=None,
            claude_model="claude-opus-5-5",
            chat_rate_limit_per_minute=30,
            likeness_provider=None,
            voice_provider=None,
        )
        app = create_app(dataclasses.replace(settings, **overrides))
        app.dependency_overrides[get_claude_client] = lambda: fake_claude
        created.append(app)
        return app

    yield _make
    for app in created:
        app.state.engine.dispose()


@pytest.fixture
def app(make_app):
    return make_app()


@pytest.fixture
def client(app) -> TestClient:
    return TestClient(app)


@pytest.fixture
def blob_dir(app) -> Path:
    return app.state.settings.blob_dir


def blob_files(blob_dir: Path) -> set[str]:
    return {p.name for p in blob_dir.iterdir()}


@pytest.fixture
def pair(client):
    """Arjun invites, Maya joins. Returns (arjun, maya)."""

    def _pair(a_name: str = "Arjun", b_name: str = "Maya") -> tuple[Api, Api]:
        a, b = Api(client, a_name), Api(client, b_name)
        code = a.post("/v1/couple/invite").json()["code"]
        r = b.post("/v1/couple/join", json={"code": code})
        assert r.status_code == 200, r.text
        return a, b

    return _pair


@pytest.fixture
def active(pair):
    """Maya consents to likeness and approves her appearance, so Arjun can chat with the companion
    modelled on Maya. Returns (arjun_viewer, maya_subject, companion_id)."""

    def _active() -> tuple[Api, Api, str]:
        arjun, maya = pair()
        cid = maya.of_me()["id"]
        assert maya.consent(cid, likeness=True).status_code == 200
        r = maya.put(f"/v1/companions/{cid}/appearance", json=APPEARANCE)
        assert r.status_code == 200 and r.json()["status"] == "active", r.text
        return arjun, maya, cid

    return _active


def parse_sse(body: str) -> list[tuple[str, dict[str, Any]]]:
    events = []
    for block in body.strip().split("\n\n"):
        name, data = None, None
        for line in block.splitlines():
            if line.startswith("event: "):
                name = line[len("event: "):]
            elif line.startswith("data: "):
                data = json.loads(line[len("data: "):])
        events.append((name, data))
    return events
