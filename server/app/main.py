"""FastAPI application factory. Run with:

    .venv/Scripts/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import logging
from pathlib import Path

from fastapi import FastAPI

from .config import Settings
from .db import init_db, make_engine, make_session_factory
from .providers import build_providers
from .ratelimit import RateLimiter
from .routers import account, chat, companions, couple, memories, privacy, service, wardrobe
from .storage import BlobStore, load_or_create_key

logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logging.getLogger("app").setLevel(logging.INFO)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    if settings.database_url.startswith("sqlite:///"):
        Path(settings.database_url.removeprefix("sqlite:///")).parent.mkdir(parents=True, exist_ok=True)

    engine = make_engine(settings.database_url)
    init_db(engine)

    app = FastAPI(title="Her API", version="1")
    app.state.settings = settings
    app.state.engine = engine
    app.state.session_factory = make_session_factory(engine)
    app.state.blob_store = BlobStore(settings.blob_dir, load_or_create_key(settings.storage_key, settings.data_dir))
    app.state.providers = build_providers(settings.likeness_provider, settings.voice_provider)
    app.state.chat_rate_limiter = RateLimiter(settings.chat_rate_limit_per_minute)

    for module in (service, account, couple, companions, wardrobe, memories, privacy, chat):
        app.include_router(module.router)
    return app


app = create_app()
