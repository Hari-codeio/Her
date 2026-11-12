# Her API contract (v1)

The Android app talks to the FastAPI server in `server/`. This file is the source of truth for that
interface: change it here first, then in both the server and `android/app/src/main/java/app/her/data/remote/`.

- Base path: `/v1`. JSON bodies, `snake_case` keys, dates as `YYYY-MM-DD`, timestamps as ISO-8601 UTC (`2026-11-12T07:21:00Z`).
- Auth: `Authorization: Bearer <token>` on every endpoint except `GET /v1/config` and `POST /v1/auth/register`.
- Errors: FastAPI's default shape `{"detail": "<machine_code or message>"}`. Machine codes used by the app are listed per endpoint.
  - `401 unauthorized`, `403 forbidden` (wrong role), `404 not_found`, `409 conflict`, `422` validation, `503 <provider>_not_configured`.
- IDs are opaque strings (UUID4 hex).

## Roles and consent (read this first)

A **couple** has exactly two **members**. Pairing creates two **companions**, one in each direction:

| Field | Meaning |
|---|---|
| `subject` | The real person the companion represents. Owns the likeness, voice, persona and wardrobe. |
| `viewer` | The partner who sees and talks to the companion. |

Rules the server enforces:

1. Nothing about a likeness exists until the **subject**, from their own account, grants `likeness` consent. No photo upload, no appearance version, no wardrobe item and no chat is accepted before that (`403 likeness_consent_required`).
2. Appearance, persona and wardrobe items authored by the viewer start as **pending** and are used only after the subject approves them. Items the subject authors are approved on save.
3. The companion is `active` only when `likeness` consent is granted **and** an approved appearance exists.
4. Revoking `likeness` immediately sets status `revoked` and permanently deletes photos, appearance versions and likeness renders. Revoking `voice` deletes voice samples and any cloned voice. Revoking `personality` stops the persona being sent to the model (chat continues with a neutral warm style).
5. `ai_training` defaults to `false` and nothing in this codebase ever trains on user data; the flag is recorded so a future pipeline must check it.
6. Every consent change is appended to an immutable consent ledger with the exact statement text shown to the subject.
7. Notification content from the phone **never** reaches the server. The app may send only a short hint such as `"WhatsApp message from Maya"` in chat context, and only when the user enables "Companion reacts to notifications".

## Objects

```jsonc
// User
{ "id": "u1", "display_name": "Arjun", "created_at": "..." }

// Couple
{ "id": "c1", "members": [User, User], "created_at": "...", "anniversary_date": "2021-02-14" | null }

// Consents
{ "likeness": false, "voice": false, "personality": false, "ai_training": false }

// Appearance
{
  "presentation": "feminine" | "masculine" | "neutral",
  "skin_tone": "#C68E6A",
  "hair_style": "long_straight" | "long_wavy" | "curly" | "bob" | "pixie" | "bun" | "ponytail" | "braids" | "short" | "buzz",
  "hair_color": "#2B1B12",
  "eye_color": "#3B2416",
  "face_shape": "oval" | "round" | "heart" | "square" | "long",
  "height": 0.5,          // 0..1 relative
  "build": 0.4,           // 0..1 slim..full
  "facial_hair": "none" | "stubble" | "beard" | "mustache",
  "glasses": false,
  "style_notes": "loves pastel kurtis and white sneakers"   // <= 500 chars
}

// AppearanceVersion
{ "id": "av1", "appearance": Appearance, "status": "pending" | "approved" | "rejected",
  "authored_by": "u1", "created_at": "...", "decided_at": "..." | null, "reject_reason": null }

// Persona (all fields optional strings / string lists; lists <= 20 items, strings <= 500 chars)
{
  "nickname_for_viewer": "jaan",
  "speaking_style": "playful, teasing, short texts, lots of emojis",
  "languages": ["English", "Hindi"],
  "interests": ["indie music", "baking"],
  "favorite_things": ["jasmine tea", "rainy evenings"],
  "pet_phrases": ["arey yaar"],
  "boundaries": ["don't talk about my job"],
  "about": "Grew up in Pune, architect, dreams of a beach house."
}

// Companion
{
  "id": "cp1",
  "subject": User, "viewer": User,
  "name": "Maya",                                     // defaults to subject.display_name
  "status": "awaiting_consent" | "draft" | "active" | "revoked",
  "consents": Consents,
  "appearance": Appearance | null,                    // latest APPROVED
  "pending_appearance": AppearanceVersion | null,     // latest pending, if any
  "persona": Persona | null,                          // APPROVED persona (null if personality consent is off)
  "pending_persona": Persona | null,
  "voice": { "status": "none" | "processing" | "ready", "samples": 0 },
  "photo_count": 0,
  "created_at": "...", "updated_at": "..."
}
```

`status` derivation: `revoked` if likeness consent was granted once and is now off; `awaiting_consent` if likeness consent was never granted; `active` if likeness consent is on and an approved appearance exists; otherwise `draft`.

When the caller is the **viewer** and status is `awaiting_consent` or `revoked`, `appearance`, `pending_appearance`, `persona` and `pending_persona` are `null`.

```jsonc
// WardrobeItem
{
  "id": "w1", "companion_id": "cp1",
  "name": "Lavender kurti",
  "category": "top" | "tshirt" | "shirt" | "dress" | "jeans" | "trousers" | "skirt" | "shorts" | "traditional" | "outerwear" | "nightwear" | "shoes" | "accessory" | "jewellery" | "bag",
  "colors": ["#B9A6E0"],                 // 1..3 hex colors, primary first
  "pattern": "solid" | "striped" | "floral" | "checked" | "dotted" | "printed",
  "styles": ["casual", "traditional"],   // subset of casual, formal, traditional, party, sporty, lounge, night, festive, romantic
  "seasons": ["spring", "summer"],       // empty = all seasons
  "warmth": 2,                           // 1 (very light) .. 5 (very warm)
  "approved": true,
  "added_by": "u1",
  "created_at": "..."
}

// Memory
{
  "id": "m1", "couple_id": "c1",
  "kind": "moment" | "date" | "place" | "message" | "preference",
  "title": "First trip to Goa", "body": "...",
  "happened_on": "2022-12-28" | null, "place": "Palolem" | null,
  "has_photo": false,
  "is_fictional": false,           // true only for AI-generated stories the user chose to keep
  "recall_allowed": true,          // companion may bring it up
  "created_by": "u1", "created_at": "...", "updated_at": "..."
}

// MemorySuggestion (proposed by the companion during chat, user must accept)
{ "id": "ms1", "companion_id": "cp1", "text": "You love jasmine tea", "created_at": "..." }

// Occasion
{
  "id": "o1", "couple_id": "c1",
  "kind": "birthday" | "anniversary" | "custom",
  "title": "Maya's birthday", "date": "1996-03-09",
  "recurs_yearly": true,
  "person_user_id": "u2" | null,
  "companion_may_initiate": true,
  "created_by": "u1"
}

// ChatMessage
{ "id": "msg1", "role": "user" | "companion", "text": "...", "kind": "chat" | "game" | "greeting",
  "game": "truth_or_dare" | null, "created_at": "..." }

// ConsentEvent (ledger row)
{ "id": "ce1", "companion_id": "cp1", "scope": "likeness" | "voice" | "personality" | "ai_training",
  "granted": true, "statement": "<exact text shown>", "statement_version": "2026-11-01", "created_at": "..." }
```

## Endpoints

### Service

| Method | Path | Auth | Body | Response |
|---|---|---|---|---|
| GET | `/v1/config` | no | | `{ "chat_available": true, "likeness_provider": false, "voice_provider": false, "consent_statement_version": "2026-11-01", "consent_statements": { "likeness": "...", "voice": "...", "personality": "...", "ai_training": "..." } }` |
| GET | `/healthz` | no | | `{ "ok": true }` |

### Account

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/v1/auth/register` | `{ "display_name": "Arjun" }` (1..40 chars) | `201 { "user": User, "token": "..." }` |
| GET | `/v1/me` | | `{ "user": User, "couple": Couple \| null, "partner": User \| null }` |
| PATCH | `/v1/me` | `{ "display_name"?: str }` | `User` |
| DELETE | `/v1/me` | | `204` - deletes the account and every row and file it owns or is subject of; dissolves the couple |

### Pairing

| Method | Path | Body | Response / errors |
|---|---|---|---|
| POST | `/v1/couple/invite` | | `{ "code": "K7Q2-M9XD", "expires_at": "..." }` valid 24h, single use. `409 already_paired` |
| POST | `/v1/couple/join` | `{ "code": "K7Q2-M9XD" }` | `Couple` and both companions are created. `404 invalid_code`, `409 already_paired`, `409 own_code` |
| GET | `/v1/couple` | | `Couple` or `404 not_paired` |
| PATCH | `/v1/couple` | `{ "anniversary_date": "2021-02-14" \| null }` | `Couple` |
| DELETE | `/v1/couple` | | `204` unpair: deletes both companions and all their data, memories, occasions |

### Companions

`{id}` must be a companion where the caller is subject or viewer, else `404 not_found`.

| Method | Path | Who | Body | Response / errors |
|---|---|---|---|---|
| GET | `/v1/companions/mine` | any | | `Companion` where caller is viewer, or `404 not_paired` |
| GET | `/v1/companions/of-me` | any | | `Companion` where caller is subject, or `404 not_paired` |
| GET | `/v1/companions/{id}` | both | | `Companion` |
| PATCH | `/v1/companions/{id}` | subject | `{ "name"?: str }` | `Companion` |
| PUT | `/v1/companions/{id}/consent` | subject | `Consents` + `"statement_version": "2026-11-01"` | `Companion`. `409 stale_statement` if version differs from config |
| GET | `/v1/companions/{id}/consent-history` | both | | `[ConsentEvent]` newest first |
| PUT | `/v1/companions/{id}/appearance` | both | `Appearance` | `Companion`. Subject: approved at once. Viewer: pending (replaces any older pending) |
| POST | `/v1/companions/{id}/appearance/{version_id}/approve` | subject | | `Companion` |
| POST | `/v1/companions/{id}/appearance/{version_id}/reject` | subject | `{ "reason"?: str }` | `Companion` |
| PUT | `/v1/companions/{id}/persona` | both | `Persona` | `Companion`. Subject: approved. Viewer: pending |
| POST | `/v1/companions/{id}/persona/approve` | subject | | `Companion` |
| POST | `/v1/companions/{id}/persona/reject` | subject | | `Companion` |
| GET | `/v1/companions/{id}/photos` | both | | `[{ "id", "uploaded_by", "created_at", "content_type" }]` |
| POST | `/v1/companions/{id}/photos` | both | multipart `file` (jpeg/png/webp, <= 10 MB, max 20 photos) | photo object. Encrypted at rest |
| GET | `/v1/companions/{id}/photos/{photo_id}` | both | | image bytes |
| DELETE | `/v1/companions/{id}/photos/{photo_id}` | subject or uploader | | `204` |
| POST | `/v1/companions/{id}/likeness/generate` | both | | `202 { "status": "processing" }` or `503 likeness_provider_not_configured` |
| GET | `/v1/companions/{id}/likeness` | both | | `{ "status": "none" \| "processing" \| "ready" \| "unavailable", "assets": [] }` |
| POST | `/v1/companions/{id}/voice/samples` | subject | multipart `file` (audio, <= 10 MB) | `{ "status", "samples" }`. Needs `voice` consent: `403 voice_consent_required` |
| POST | `/v1/companions/{id}/voice/speak` | viewer | `{ "text": str }` (<= 1000 chars) | `audio/mpeg` bytes, or `503 voice_provider_not_configured` / `409 voice_not_ready` |

### Wardrobe

| Method | Path | Who | Body | Response |
|---|---|---|---|---|
| GET | `/v1/companions/{id}/wardrobe` | both | | `[WardrobeItem]`. Viewer sees approved items and their own pending ones |
| POST | `/v1/companions/{id}/wardrobe` | both | WardrobeItem fields without `id/approved/added_by/created_at/companion_id` | `201 WardrobeItem` |
| PATCH | `/v1/companions/{id}/wardrobe/{item_id}` | subject or author | partial | `WardrobeItem` (viewer edit resets `approved` to false) |
| DELETE | `/v1/companions/{id}/wardrobe/{item_id}` | subject or author | | `204` |
| POST | `/v1/companions/{id}/wardrobe/{item_id}/approve` | subject | `{ "approved": bool }` | `WardrobeItem` (false deletes the item and returns `204`) |

### Memories and occasions (couple-scoped, either member)

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/v1/memories` | | `[Memory]` ordered by `happened_on` desc, nulls last |
| POST | `/v1/memories` | Memory fields without server fields | `201 Memory` |
| PATCH | `/v1/memories/{id}` | partial | `Memory` |
| DELETE | `/v1/memories/{id}` | | `204` |
| PUT | `/v1/memories/{id}/photo` | multipart `file` | `Memory` |
| GET | `/v1/memories/{id}/photo` | | image bytes |
| GET | `/v1/occasions` | | `[Occasion]` |
| POST | `/v1/occasions` | Occasion fields without server fields | `201 Occasion` |
| PATCH | `/v1/occasions/{id}` | partial | `Occasion` |
| DELETE | `/v1/occasions/{id}` | | `204` |

### Privacy

| Method | Path | Response |
|---|---|---|
| GET | `/v1/privacy/export` | JSON with everything about the caller: user, couple, both companions (consents, appearance versions, persona, voice status, photo metadata without bytes), consent ledger, wardrobe of the companion where the caller is subject, memories, occasions, and the caller's chat messages |
| DELETE | `/v1/privacy/photos` | `204` deletes every companion photo where the caller is subject or uploader, and photos on memories the caller created (rows and blobs) |
| DELETE | `/v1/privacy/voice` | `204` deletes voice samples and any cloned voice where the caller is subject |
| DELETE | `/v1/privacy/memories` | `204` deletes memories (and their photos) the caller created |

### Chat (viewer only)

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/v1/companions/{id}/messages?limit=50&before=<message_id>` | | `[ChatMessage]` oldest first |
| DELETE | `/v1/companions/{id}/messages` | | `204` |
| GET | `/v1/companions/{id}/memory-suggestions` | | `[MemorySuggestion]` |
| POST | `/v1/companions/{id}/memory-suggestions/{sid}/accept` | | `201 Memory` (kind `preference`) |
| POST | `/v1/companions/{id}/memory-suggestions/{sid}/dismiss` | | `204` |
| POST | `/v1/companions/{id}/greeting` | `{ "context": ChatContext }` | `{ "text": str }` short in-character greeting, saved as kind `greeting` |
| POST | `/v1/companions/{id}/chat` | see below | `text/event-stream` |

Chat request:

```jsonc
{
  "text": "how's your day going?",          // 1..4000 chars
  "mode": "chat" | "game",
  "game": "truth_or_dare" | "guessing" | "quiz" | "random_questions" | "story" | "virtual_date" | "would_you_rather" | null,
  "context": {                              // ChatContext - all optional, set by the app
    "local_time": "2026-11-12T21:04:00+05:30",
    "day_part": "morning" | "afternoon" | "evening" | "night",
    "mood": "playful",
    "activity": "reading",
    "environment": "cafe",
    "outfit_summary": "lavender kurti, white jeans, silver jhumkas",
    "weather": "22°C light rain",
    "occasion_today": "Anniversary (5 years)",
    "notification_hint": "WhatsApp message from Maya"
  }
}
```

Server-sent events, in order:

```
event: delta              data: {"text": "partial text"}        (many)
event: memory_suggestion  data: MemorySuggestion                 (0..n)
event: done               data: {"user_message_id": "...", "message_id": "..."}
event: error              data: {"detail": "chat_unavailable" | "rate_limited" | "declined" | "..."}   (instead of done)
```

Chat errors before the stream starts: `403 likeness_consent_required`, `403 forbidden` (caller is the subject), `409 companion_not_active`.
