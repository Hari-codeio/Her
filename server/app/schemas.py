"""Request models (validation) and response serializers (JSON shapes from docs/API.md)."""
from __future__ import annotations

from datetime import date as Date
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import models as m
from .consent import approved_appearance, companion_status, pending_appearance
from .util import iso, iso_date

# ---------------------------------------------------------------- shared field types

HexColor = Annotated[str, StringConstraints(pattern=r"^#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")]
DisplayName = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=40)]
Str500 = Annotated[str, StringConstraints(max_length=500)]
Str200 = Annotated[str, StringConstraints(max_length=200)]
Title = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]
Body = Annotated[str, StringConstraints(max_length=5000)]
PersonaList = Annotated[list[Str500], Field(max_length=20)]


class In(BaseModel):
    model_config = ConfigDict(extra="ignore")


# ---------------------------------------------------------------- account / pairing

class RegisterIn(In):
    display_name: DisplayName


class MePatch(In):
    display_name: DisplayName = None  # type: ignore[assignment]  (absent = unchanged, null = 422)


class JoinIn(In):
    code: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=20)]


class CouplePatch(In):
    anniversary_date: Date | None = None


# ---------------------------------------------------------------- companions

class CompanionPatch(In):
    name: DisplayName | None = None  # null resets to the subject's display name


class ConsentIn(In):
    likeness: bool | None = None
    voice: bool | None = None
    personality: bool | None = None
    ai_training: bool | None = None
    statement_version: str


Presentation = Literal["feminine", "masculine", "neutral"]
HairStyle = Literal["long_straight", "long_wavy", "curly", "bob", "pixie", "bun", "ponytail", "braids", "short", "buzz"]
FaceShape = Literal["oval", "round", "heart", "square", "long"]
FacialHair = Literal["none", "stubble", "beard", "mustache"]


class AppearanceIn(In):
    presentation: Presentation
    skin_tone: HexColor
    hair_style: HairStyle
    hair_color: HexColor
    eye_color: HexColor
    face_shape: FaceShape
    height: float = Field(ge=0.0, le=1.0)
    build: float = Field(ge=0.0, le=1.0)
    facial_hair: FacialHair = "none"
    glasses: bool = False
    style_notes: Str500 = ""


class RejectIn(In):
    reason: Str500 | None = None


PERSONA_STR_FIELDS = ("nickname_for_viewer", "speaking_style", "about")
PERSONA_LIST_FIELDS = ("languages", "interests", "favorite_things", "pet_phrases", "boundaries")


class PersonaIn(In):
    nickname_for_viewer: Str500 | None = None
    speaking_style: Str500 | None = None
    languages: PersonaList | None = None
    interests: PersonaList | None = None
    favorite_things: PersonaList | None = None
    pet_phrases: PersonaList | None = None
    boundaries: PersonaList | None = None
    about: Str500 | None = None


# ---------------------------------------------------------------- wardrobe

WardrobeCategory = Literal[
    "top", "tshirt", "shirt", "dress", "jeans", "trousers", "skirt", "shorts", "traditional",
    "outerwear", "nightwear", "shoes", "accessory", "jewellery", "bag",
]
Pattern = Literal["solid", "striped", "floral", "checked", "dotted", "printed"]
Style = Literal["casual", "formal", "traditional", "party", "sporty", "lounge", "night", "festive", "romantic"]
Season = Literal["spring", "summer", "autumn", "winter", "monsoon"]
ItemName = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]
Colors = Annotated[list[HexColor], Field(min_length=1, max_length=3)]


def _dedupe(values: list[str] | None) -> list[str] | None:
    if values is None:
        return None
    return list(dict.fromkeys(values))


class WardrobeIn(In):
    name: ItemName
    category: WardrobeCategory
    colors: Colors
    pattern: Pattern = "solid"
    styles: Annotated[list[Style], Field(max_length=9)] = []
    seasons: Annotated[list[Season], Field(max_length=5)] = []
    warmth: int = Field(default=3, ge=1, le=5)

    _dedupe_lists = field_validator("styles", "seasons")(_dedupe)


class WardrobePatch(In):
    # Absent = unchanged; explicit null is rejected because none of these columns are nullable.
    name: ItemName = None  # type: ignore[assignment]
    category: WardrobeCategory = None  # type: ignore[assignment]
    colors: Colors = None  # type: ignore[assignment]
    pattern: Pattern = None  # type: ignore[assignment]
    styles: Annotated[list[Style], Field(max_length=9)] = None  # type: ignore[assignment]
    seasons: Annotated[list[Season], Field(max_length=5)] = None  # type: ignore[assignment]
    warmth: int = Field(default=None, ge=1, le=5)  # type: ignore[assignment]

    _dedupe_lists = field_validator("styles", "seasons")(_dedupe)


class WardrobeApproveIn(In):
    approved: bool


# ---------------------------------------------------------------- memories / occasions

MemoryKind = Literal["moment", "date", "place", "message", "preference"]
OccasionKind = Literal["birthday", "anniversary", "custom"]


class MemoryIn(In):
    kind: MemoryKind
    title: Title
    body: Body = ""
    happened_on: Date | None = None
    place: Str200 | None = None
    is_fictional: bool = False
    recall_allowed: bool = True


class MemoryPatch(In):
    kind: MemoryKind = None  # type: ignore[assignment]
    title: Title = None  # type: ignore[assignment]
    body: Body = None  # type: ignore[assignment]
    happened_on: Date | None = None
    place: Str200 | None = None
    is_fictional: bool = None  # type: ignore[assignment]
    recall_allowed: bool = None  # type: ignore[assignment]


class OccasionIn(In):
    kind: OccasionKind
    title: Title
    date: Date
    recurs_yearly: bool = True
    person_user_id: str | None = None
    companion_may_initiate: bool = True


class OccasionPatch(In):
    kind: OccasionKind = None  # type: ignore[assignment]
    title: Title = None  # type: ignore[assignment]
    date: Date = None  # type: ignore[assignment]
    recurs_yearly: bool = None  # type: ignore[assignment]
    person_user_id: str | None = None
    companion_may_initiate: bool = None  # type: ignore[assignment]


# ---------------------------------------------------------------- chat

GameName = Literal["truth_or_dare", "guessing", "quiz", "random_questions", "story", "virtual_date", "would_you_rather"]
Ctx = Annotated[str, StringConstraints(strip_whitespace=True, max_length=200)]


class ChatContext(In):
    local_time: Annotated[str, StringConstraints(strip_whitespace=True, max_length=64)] | None = None
    day_part: Literal["morning", "afternoon", "evening", "night"] | None = None
    mood: Ctx | None = None
    activity: Ctx | None = None
    environment: Ctx | None = None
    outfit_summary: Ctx | None = None
    weather: Ctx | None = None
    occasion_today: Ctx | None = None
    # Rule 7: only a short hint ("WhatsApp message from Maya"), never notification content.
    notification_hint: Annotated[str, StringConstraints(strip_whitespace=True, max_length=120)] | None = None


class ChatIn(In):
    text: Annotated[str, StringConstraints(min_length=1, max_length=4000)]
    mode: Literal["chat", "game"] = "chat"
    game: GameName | None = None
    context: ChatContext = Field(default_factory=ChatContext)


class GreetingIn(In):
    context: ChatContext = Field(default_factory=ChatContext)


class SpeakIn(In):
    text: Annotated[str, StringConstraints(min_length=1, max_length=1000)]


# ================================================================ serializers

def user_out(u: m.User) -> dict[str, Any]:
    return {"id": u.id, "display_name": u.display_name, "created_at": iso(u.created_at)}


def couple_out(db: Session, c: m.Couple) -> dict[str, Any]:
    members = [db.get(m.User, c.user_a_id), db.get(m.User, c.user_b_id)]
    return {
        "id": c.id,
        "members": [user_out(u) for u in members if u is not None],
        "created_at": iso(c.created_at),
        "anniversary_date": iso_date(c.anniversary_date),
    }


def persona_out(data: dict[str, Any] | None) -> dict[str, Any] | None:
    if data is None:
        return None
    out: dict[str, Any] = {}
    for key in ("nickname_for_viewer", "speaking_style", "languages", "interests",
                "favorite_things", "pet_phrases", "boundaries", "about"):
        if key in PERSONA_LIST_FIELDS:
            out[key] = list(data.get(key) or [])
        else:
            out[key] = data.get(key)
    return out


def appearance_version_out(v: m.AppearanceVersion) -> dict[str, Any]:
    return {
        "id": v.id,
        "appearance": dict(v.data),
        "status": v.status,
        "authored_by": v.authored_by,
        "created_at": iso(v.created_at),
        "decided_at": iso(v.decided_at),
        "reject_reason": v.reject_reason,
    }


def consents_out(c: m.Companion) -> dict[str, bool]:
    return {
        "likeness": c.consent_likeness,
        "voice": c.consent_voice,
        "personality": c.consent_personality,
        "ai_training": c.consent_ai_training,
    }


def companion_out(db: Session, c: m.Companion, caller_id: str) -> dict[str, Any]:
    subject = db.get(m.User, c.subject_id)
    viewer = db.get(m.User, c.viewer_id)
    status = companion_status(db, c)
    approved = approved_appearance(db, c.id)
    pending = pending_appearance(db, c.id)
    hide = caller_id == c.viewer_id and status in ("awaiting_consent", "revoked")
    samples = db.scalar(select(func.count()).select_from(m.VoiceSample).where(m.VoiceSample.companion_id == c.id)) or 0
    photos = db.scalar(select(func.count()).select_from(m.Photo).where(m.Photo.companion_id == c.id)) or 0
    return {
        "id": c.id,
        "subject": user_out(subject),
        "viewer": user_out(viewer),
        "name": c.name or subject.display_name,
        "status": status,
        "consents": consents_out(c),
        "appearance": None if hide or approved is None else dict(approved.data),
        "pending_appearance": None if hide or pending is None else appearance_version_out(pending),
        "persona": None if hide or not c.consent_personality else persona_out(c.persona),
        "pending_persona": None if hide else persona_out(c.pending_persona),
        "voice": {"status": c.voice_status, "samples": samples},
        "photo_count": photos,
        "created_at": iso(c.created_at),
        "updated_at": iso(c.updated_at),
    }


def photo_out(p: m.Photo) -> dict[str, Any]:
    return {"id": p.id, "uploaded_by": p.uploaded_by, "created_at": iso(p.created_at), "content_type": p.content_type}


def wardrobe_out(w: m.WardrobeItem) -> dict[str, Any]:
    return {
        "id": w.id,
        "companion_id": w.companion_id,
        "name": w.name,
        "category": w.category,
        "colors": list(w.colors),
        "pattern": w.pattern,
        "styles": list(w.styles),
        "seasons": list(w.seasons),
        "warmth": w.warmth,
        "approved": w.approved,
        "added_by": w.added_by,
        "created_at": iso(w.created_at),
    }


def memory_out(x: m.Memory) -> dict[str, Any]:
    return {
        "id": x.id,
        "couple_id": x.couple_id,
        "kind": x.kind,
        "title": x.title,
        "body": x.body,
        "happened_on": iso_date(x.happened_on),
        "place": x.place,
        "has_photo": x.photo_blob is not None,
        "is_fictional": x.is_fictional,
        "recall_allowed": x.recall_allowed,
        "created_by": x.created_by,
        "created_at": iso(x.created_at),
        "updated_at": iso(x.updated_at),
    }


def occasion_out(o: m.Occasion) -> dict[str, Any]:
    return {
        "id": o.id,
        "couple_id": o.couple_id,
        "kind": o.kind,
        "title": o.title,
        "date": iso_date(o.date),
        "recurs_yearly": o.recurs_yearly,
        "person_user_id": o.person_user_id,
        "companion_may_initiate": o.companion_may_initiate,
        "created_by": o.created_by,
    }


def message_out(x: m.ChatMessage) -> dict[str, Any]:
    return {"id": x.id, "role": x.role, "text": x.text, "kind": x.kind, "game": x.game, "created_at": iso(x.created_at)}


def suggestion_out(s: m.MemorySuggestion) -> dict[str, Any]:
    return {"id": s.id, "companion_id": s.companion_id, "text": s.text, "created_at": iso(s.created_at)}


def consent_event_out(e: m.ConsentEvent) -> dict[str, Any]:
    return {
        "id": e.id,
        "companion_id": e.companion_id,
        "scope": e.scope,
        "granted": e.granted,
        "statement": e.statement,
        "statement_version": e.statement_version,
        "created_at": iso(e.created_at),
    }
