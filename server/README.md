# Her server

FastAPI backend for Her. The API contract is [`docs/API.md`](../docs/API.md); this server implements
it exactly (paths, fields, status codes, `detail` machine codes, SSE events, consent rules).

Stack: FastAPI + uvicorn, Pydantic v2, SQLAlchemy 2 on SQLite, Fernet-encrypted blob storage, the
official `anthropic` SDK for chat, pytest for tests.

## Setup (Windows, Git Bash, from the repo root)

```bash
python -m venv server/.venv
server/.venv/Scripts/python -m pip install -r server/requirements.txt
```

`SQLAlchemy` is pinned to 2.0.x on purpose: 2.1 ships compiled extensions it cannot run without,
and Windows Application Control blocked them on the dev machine. 2.0 falls back to pure Python.

## Run

From `server/`:

```bash
.venv/Scripts/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

From the repo root the equivalent is
`server/.venv/Scripts/python -m uvicorn app.main:app --app-dir server --host 0.0.0.0 --port 8000`.

Check it: `curl http://127.0.0.1:8000/healthz` returns `{"ok": true}` and
`curl http://127.0.0.1:8000/v1/config` lists the consent statements.

On first run the server creates `server/data/` with the SQLite database (`her.db`), the storage key
(`storage.key`, if `HER_STORAGE_KEY` isn't set) and encrypted blobs (`blobs/`). `server/data/` is
git-ignored. Losing `storage.key` makes every stored photo and voice sample unreadable.

### From the Android emulator

The emulator reaches the host machine at `http://10.0.2.2:8000`, so point the app's base URL there
(the server must listen on `0.0.0.0` or `127.0.0.1`; both work for the emulator). A physical phone
on the same Wi-Fi uses the PC's LAN IP instead, for example `http://192.168.1.20:8000`, and may need
a Windows Firewall rule for port 8000. Traffic is plain HTTP, so keep this to local development.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `HER_DATABASE_URL` | `sqlite:///server/data/her.db` | SQLAlchemy URL |
| `HER_DATA_DIR` | `server/data` | Where the DB, key and blobs live |
| `HER_BLOB_DIR` | `<data dir>/blobs` | Encrypted file storage |
| `HER_STORAGE_KEY` | generated into `<data dir>/storage.key` | Fernet key for blobs |
| `HER_CLAUDE_MODEL` | `claude-opus-5-5` | Model for chat and greetings |
| `HER_CHAT_RATE_LIMIT` | `30` | `/chat` + `/greeting` requests per user per minute |
| `HER_LIKENESS_PROVIDER` | unset (not configured) | `package.module:ClassName` of a `LikenessProvider` |
| `HER_VOICE_PROVIDER` | unset (not configured) | `package.module:ClassName` of a `VoiceProvider` |
| `ANTHROPIC_API_KEY` | unset | Claude credentials (or use an `ant auth login` profile) |

The server starts without Claude credentials. `/v1/config` then reports `"chat_available": false`
and `/chat` answers with an SSE `error` event `chat_unavailable` (`/greeting` with `503`).

## Tests

```bash
cd server
.venv/Scripts/python -m pytest -q
```

Tests run against a temporary SQLite DB and blob dir per test and inject a fake Claude client through
the `get_claude_client` dependency; they never call the real API.

## Trying the whole flow with one phone: `scripts/simulate_partner.py`

The script plays your partner through the public API only (it registers, joins, consents and sets
things up exactly like a second phone would). Standard library only, so any Python 3.10+ works.

1. Start the server, open the app, register, and create an invite code.
2. Have the simulated partner join with that code:

   ```bash
   python scripts/simulate_partner.py join http://127.0.0.1:8000 K7Q2-M9XD --name Maya
   ```

   It registers Maya, joins your couple, saves her token to `server/data/simulated_partner.json`,
   grants `likeness`, `voice` and `personality` consent (using the statements from `/v1/config`;
   `ai_training` stays off), sets an appearance and persona for Maya's own companion (the one you
   talk to), adds 18 varied wardrobe items and Maya's birthday (three weeks from today, so the
   companion sees it as upcoming). The companion modelled on Maya is now `active` for you.
3. Anything you propose for Maya's companion from the app (appearance, persona, wardrobe items) stays
   pending until Maya approves it:

   ```bash
   python scripts/simulate_partner.py approve http://127.0.0.1:8000
   ```

4. Test revocation (the companion becomes `revoked`, photos/appearance/wardrobe are deleted):

   ```bash
   python scripts/simulate_partner.py revoke http://127.0.0.1:8000 likeness
   ```

   `voice`, `personality` and `ai_training` can be revoked the same way.

Your own companion (the one Maya would see) stays `awaiting_consent` until you grant consent in the app.

## Consent model (summary of docs/API.md)

Pairing creates two companions, one per direction. The **subject** is the real person a companion
represents and owns its likeness, voice, persona and wardrobe; the **viewer** is the partner who
talks to it.

- Nothing likeness-related (photos, appearance versions, wardrobe items, chat, greetings, likeness
  generation) is accepted until the subject grants `likeness` consent: `403 likeness_consent_required`.
- Appearance, persona and wardrobe items written by the viewer are `pending` until the subject
  approves them; the subject's own edits are approved on save.
- Status: `awaiting_consent` (likeness never granted), `revoked` (granted once, now off), `active`
  (likeness on and an approved appearance), otherwise `draft`. While the companion is
  `awaiting_consent` or `revoked`, the viewer sees no appearance or persona.
- Revoking `likeness` immediately deletes photos, appearance versions, likeness renders and the
  wardrobe (DB rows and encrypted files). Revoking `voice` deletes voice samples and any cloned
  voice. Revoking `personality` stops the persona being sent to the model; chat continues in a
  neutral warm style.
- `ai_training` defaults to off and is only recorded; nothing here trains on user data.
- Every consent change appends a ledger row with the exact statement text and its version
  (`2026-11-01`). A `PUT .../consent` with another `statement_version` gets `409 stale_statement`.
- Notification content never reaches the server; chat context may carry only a short
  `notification_hint` (at most 120 characters).
- Deletion is real: `DELETE /v1/me`, `DELETE /v1/couple`, photo deletes, revocations and the
  `/v1/privacy/*` deletes remove rows and blob files. `GET /v1/privacy/export` returns the caller's data.

## Chat

`/chat` streams Claude's reply as SSE (`delta`*, `memory_suggestion`*, then `done` or `error`).
Requests go through `client.beta.messages.stream(...)` with server-side refusal fallbacks
(`betas=["server-side-fallback-2026-07-01"]`, `fallbacks="default"`), `output_config={"effort": "low"}`
and `max_tokens=8000`. The cached system block holds only stable content (rules, name, approved
persona, recall-allowed memories, upcoming occasions); per-request context (local time, mood,
scene, outfit, weather, game mode, notification hint) goes in a trailing mid-conversation `system`
message so the prompt cache stays warm. The model can call one tool, `suggest_memory`; suggestions
are stored and the user accepts or dismisses them. A refusal ends the stream with `error` /
`declined`. Code: `app/ai/claude.py` and `app/ai/prompts.py`.

## Provider interfaces

Likeness rendering and voice cloning are pluggable and ship as "not configured":
`/likeness/generate` returns `503 likeness_provider_not_configured`, `/voice/speak` returns
`503 voice_provider_not_configured`, and `/v1/config` reports both as `false`. Voice samples are
still stored (encrypted) and counted.

To add a real provider, subclass the ABC and point the env var at it:

- `app/providers/likeness.py` `LikenessProvider`: `start(job) -> "processing" | "ready"`,
  `poll(companion_id) -> (status, [LikenessAssetData])`, `delete(companion_id)`. The server stores
  returned assets encrypted and deletes them on revocation.
- `app/providers/voice.py` `VoiceProvider`: `add_samples(companion_id, samples) -> status`,
  `speak(companion_id, text) -> bytes` (`audio/mpeg`), `delete_voice(companion_id)`.

Providers are only called after the subject's consent and must delete everything they hold when
`delete` / `delete_voice` is called.

## Layout

```
app/
  main.py          app factory (create_app) and module-level `app`
  config.py        settings, consent statements and version
  db.py, models.py SQLAlchemy setup and tables
  schemas.py       request validation and response serializers
  auth.py          bearer tokens (only SHA-256 hashes are stored)
  storage.py       Fernet-encrypted blob store
  consent.py       status derivation, consent ledger, revocation deletes
  cleanup.py       companion/couple/account deletion
  access.py        role and consent guards
  ratelimit.py     in-memory per-user rate limit
  routers/         one module per area of the contract
  ai/              Claude client, prompts and the tool loop
  providers/       likeness and voice provider interfaces
scripts/simulate_partner.py
tests/
```
