# Her

An Android app that gives each partner in a couple a private, consent-based AI companion modelled
on the other partner. The companion has the partner's (approved) look, wears clothes from their
wardrobe and picks a new outfit every day, goes about its own little day, talks through Claude,
remembers what you choose to save, celebrates your special days, and can float over other apps
with your WhatsApp/Instagram badges underneath it.

It is always presented as an AI representation, never as the real person.

## Repository layout

| Path | What it is |
|---|---|
| `android/app` | The app: Kotlin, Jetpack Compose, Room, DataStore, WorkManager, OkHttp/SSE |
| `android/core` | Pure-Kotlin domain logic with unit tests: outfit engine, behaviour and mood, occasions, memory recall, greetings, games, notification sources |
| `server/` | FastAPI backend: accounts, pairing, consent ledger, likeness/persona/wardrobe approvals, memories, occasions, Claude chat over SSE, encrypted photo/voice storage, privacy endpoints. See [`server/README.md`](server/README.md) |
| `docs/API.md` | The contract between app and server. Change it first, then both sides |

## How consent works

This is the backbone of the product, enforced by the server rather than by the UI:

1. Pairing two accounts creates two companions, one in each direction. The person a companion
   represents is its **subject**; the partner who talks to it is the **viewer**.
2. Nothing about a likeness can exist until the subject grants **likeness** consent from their own
   phone (More → My likeness & consent). Before that, the server rejects photos, looks, clothes and chat.
3. Anything the viewer designs (look, personality, clothes) waits for the subject's approval.
   The subject sees a side-by-side preview of every proposed look.
4. Voice, personality and AI-training consent are separate switches. AI training is off by default
   and nothing in this codebase trains on user data.
5. Withdrawing likeness consent immediately deactivates the companion (the floating companion
   disappears too) and permanently deletes photos, looks and wardrobe. Every change goes into an
   append-only consent ledger with the exact wording shown.
6. Notification text never leaves the phone. At most a hint such as "WhatsApp message from Maya"
   is shared with the chat, and only if the user turns that on.
7. The companion is labelled "AI companion" on every surface, and its system prompt forbids claiming
   to be the partner, inventing real-world events, or pretending to have sent or read real messages.
   Explicit content is out of scope; warm and lightly flirty is fine.

## Feature map

| Product area | Where |
|---|---|
| Character creation, preview, partner approval, photos | Character studio (viewer) and My likeness (subject); server `companions` router |
| Smart wardrobe and automatic daily dressing | `core/wardrobe/OutfitEngine.kt`: colour harmony, weather, season, occasion, mood, no-repeat window, likes/dislikes, surprises, bedtime nightwear; Wardrobe tab (today / closet / history / manual pick) |
| Independent behaviour by time of day | `core/behavior/BehaviorEngine.kt` (13 activities, scene-aware, 4 autonomy levels, quiet hours); drawn by `ui/avatar/Avatar.kt` |
| Floating companion (head / upper body / full body) | `overlay/FloatingCompanionService.kt`: drag, tap, double-tap, long-press panel, flick to change activity, hide |
| AI voice and conversation | Claude via the server (`server/app/ai`), streamed over SSE; on-device speech recognition and text-to-speech with lip-sync; voice-conversation mode |
| WhatsApp / Instagram / other notifications | `notifications/CompanionNotificationListener.kt`: per-app opt-in, real app icons, partner detection, previews optional, local-only history |
| Daily mood and greetings | `core/behavior/MoodEngine`, `core/greeting/GreetingComposer`; morning/evening/bedtime messages by `work/CompanionWorker.kt` |
| Shared memories | Memories tab (photos, dates, places, messages); "on this day" recall; companion proposes memories, the user approves; fictional stories kept separate |
| Special occasions | Special days screen; outfits, moods and greetings change on the day; 7/3/1-day countdowns |
| Touch, gestures, games | Stage gestures and reactions; 7 games (Claude-led) plus offline truth/dare/question cards |
| Virtual environments | 7 scenes that change with morning, afternoon, evening and night |
| Privacy controls | Privacy & data screen: export, delete photos/voice/memories, unpair, delete account |

### What is real and what is a stand-in

- **The character is a stylised, procedurally drawn avatar** built from the approved appearance
  (skin tone, face shape, hairstyle and colour, eyes, height, build, facial hair, glasses) and the
  actual wardrobe pieces, with patterns, jewellery, bags and props. A **photorealistic likeness** needs
  an image-generation provider: the server has a `LikenessProvider` interface (and the app a
  "Generate realistic character" button) that is "not configured" today.
- **Voice cloning** works the same way: the subject can record encrypted voice samples (with voice
  consent), the server has a `VoiceProvider` interface, and until one is configured the app uses the
  phone's text-to-speech with the user's speed and pitch.
- **Accounts are name-only** for development. Before release this needs real sign-in (phone or
  email verification) and HTTPS.

## Running it

### 1. Server

```bash
python -m venv server/.venv
server/.venv/Scripts/python -m pip install -r server/requirements.txt
cd server
.venv/Scripts/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

For AI chat, give the server Claude credentials (`ANTHROPIC_API_KEY`, or an `ant auth login` profile)
before starting it. Without them everything else works and chat shows that it isn't set up.
Chat uses `claude-opus-5-5` (override with `HER_CLAUDE_MODEL`) with server-side refusal fallbacks
enabled (`fallbacks: "default"`), so a declined turn is retried on a fallback model instead of failing.

### 2. App

Open `android/` in Android Studio and run the `app` configuration, or build from the command line:

```bash
cd android
./gradlew :core:test :app:assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

The debug build talks to `http://10.0.2.2:8000` (the emulator's address for your computer). On a
real phone, open "Server settings" on the first screen and enter `http://<your computer's LAN IP>:8000`.
Release builds only allow HTTPS.

### 3. Trying it with one phone

Pairing needs two people. To play both roles yourself, create an invite code in the app and let the
simulator act as your partner through the public API (it does not bypass consent; it grants it as
the partner would):

```bash
cd server
.venv/Scripts/python scripts/simulate_partner.py join http://127.0.0.1:8000 ABCD-EFGH --name Maya
```

Maya joins, gives likeness/voice/personality consent, sets her look and personality, adds an
18-piece wardrobe and a birthday. Anything you then propose for Maya waits for approval:
`simulate_partner.py approve http://127.0.0.1:8000`. To see revocation:
`simulate_partner.py revoke http://127.0.0.1:8000 likeness`.

### 4. Permissions the app asks for

- Notifications (greetings and reminders from the companion)
- Display over other apps (floating companion, only when turned on)
- Notification access (only for the messaging apps switched on in More → Message notifications).
  Android 15 and later hide the sender and text of some notifications from apps like this one
  (for example SMS from unknown numbers, or one-time codes). The companion still shows the app
  badge and reacts, but can't say who it's from.
- Microphone (voice chat and voice samples, when used)

## Tests

```bash
cd android && ./gradlew :core:test          # outfit, behaviour, mood, occasions, recall, colours, matching
cd server && .venv/Scripts/python -m pytest -q   # API contract, consent rules, deletion, chat SSE with a fake Claude
```

## Before shipping

- Real authentication, HTTPS, and a production database instead of SQLite.
- Plug in a likeness provider (identity-preserving image generation) and a voice provider, each
  gated on the consents above.
- Push notifications (FCM) so the subject hears about pending approvals right away.
- Google Play declarations for the overlay, the notification listener and the special-use
  foreground service, plus a privacy policy covering likeness, voice and notification data.
- Verify that the consenting subject is the person in the photos (for example a selfie liveness
  check matched against the uploaded photos). Today someone could pair with a second account they
  control and "consent" on another person's behalf; the server-side consent model is only as strong
  as that identity link. Add an age gate and photo moderation alongside it.
- iOS (the domain logic in `android/core` is plain Kotlin and can move to Kotlin Multiplatform).
