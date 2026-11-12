#!/usr/bin/env python3
"""Play the partner so one tester with one phone can try the full Her flow.

Everything goes through the public HTTP API (no database shortcuts, no consent bypass): the
simulated partner registers, joins with your invite code, grants consent with the statements the
server publishes in /v1/config, and sets up their own companion the same way a real user would.

    python scripts/simulate_partner.py join    http://127.0.0.1:8000 K7Q2-M9XD [--name Maya]
    python scripts/simulate_partner.py approve http://127.0.0.1:8000
    python scripts/simulate_partner.py revoke  http://127.0.0.1:8000 likeness

The partner's token is saved to server/data/simulated_partner.json. Standard library only.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from datetime import date, timedelta
from pathlib import Path
from typing import Any

STATE_FILE = Path(__file__).resolve().parent.parent / "data" / "simulated_partner.json"

APPEARANCE = {
    "presentation": "feminine",
    "skin_tone": "#C68E6A",
    "hair_style": "long_wavy",
    "hair_color": "#2B1B12",
    "eye_color": "#3B2416",
    "face_shape": "oval",
    "height": 0.45,
    "build": 0.4,
    "facial_hair": "none",
    "glasses": False,
    "style_notes": "loves pastel kurtis and white sneakers",
}

PERSONA = {
    "nickname_for_viewer": "jaan",
    "speaking_style": "playful, teasing, short texts, a few emojis",
    "languages": ["English", "Hindi"],
    "interests": ["indie music", "baking", "sketching"],
    "favorite_things": ["jasmine tea", "rainy evenings", "old Bollywood songs"],
    "pet_phrases": ["arey yaar", "okay but listen"],
    "boundaries": ["don't talk about my job"],
    "about": "Grew up in Pune, architect, dreams of a beach house.",
}


def _item(name, category, colors, pattern, styles, seasons, warmth):
    return {"name": name, "category": category, "colors": colors, "pattern": pattern,
            "styles": styles, "seasons": seasons, "warmth": warmth}


WARDROBE = [
    _item("White linen shirt", "shirt", ["#F5F5F0"], "solid", ["casual", "formal"], ["spring", "summer"], 2),
    _item("Pastel lavender kurti", "traditional", ["#B9A6E0", "#FFFFFF"], "floral", ["traditional", "casual"], ["spring", "summer"], 2),
    _item("Navy striped tee", "tshirt", ["#1F3A5F", "#FFFFFF"], "striped", ["casual"], [], 2),
    _item("Mustard knit top", "top", ["#D9A21B"], "solid", ["casual"], ["autumn", "winter"], 3),
    _item("Red wrap dress", "dress", ["#B3202A"], "solid", ["party", "romantic"], ["spring", "summer", "autumn"], 2),
    _item("Floral sundress", "dress", ["#F6D6E0", "#6BAA75"], "floral", ["casual", "romantic"], ["summer"], 1),
    _item("Blue straight jeans", "jeans", ["#3B5B92"], "solid", ["casual"], [], 3),
    _item("Black high-waist jeans", "jeans", ["#1B1B1B"], "solid", ["casual", "party"], [], 3),
    _item("Pleated midi skirt", "skirt", ["#8FA9C9"], "solid", ["casual", "formal"], ["spring", "autumn"], 2),
    _item("Emerald silk saree", "traditional", ["#0F6B4F", "#D4AF37"], "printed", ["traditional", "festive"], [], 2),
    _item("Navy wool coat", "outerwear", ["#1C2541"], "solid", ["formal"], ["winter"], 5),
    _item("Denim jacket", "outerwear", ["#5B7DB1"], "solid", ["casual"], ["spring", "autumn"], 3),
    _item("Polka-dot pyjama set", "nightwear", ["#F2C6D0", "#FFFFFF"], "dotted", ["lounge", "night"], [], 2),
    _item("White sneakers", "shoes", ["#FFFFFF"], "solid", ["casual", "sporty"], [], 2),
    _item("Tan block heels", "shoes", ["#C19A6B"], "solid", ["party", "formal"], [], 2),
    _item("Silver jhumkas", "jewellery", ["#C0C0C0"], "solid", ["traditional", "festive"], [], 1),
    _item("Checked wool scarf", "accessory", ["#7B2D26", "#E8D8C4"], "checked", ["casual"], ["winter"], 4),
    _item("Brown leather tote", "bag", ["#7A4A2A"], "solid", ["casual", "formal"], [], 1),
]

SCOPES = ("likeness", "voice", "personality", "ai_training")


class ApiError(Exception):
    pass


def call(base: str, method: str, path: str, token: str | None = None, body: Any = None) -> Any:
    url = base.rstrip("/") + path
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        try:
            detail = json.loads(detail).get("detail", detail)
        except (ValueError, AttributeError):
            pass
        raise ApiError(f"{method} {path} -> HTTP {exc.code}: {detail}") from None
    except urllib.error.URLError as exc:
        raise ApiError(f"cannot reach {url}: {exc.reason}") from None


def load_state() -> dict[str, Any]:
    if not STATE_FILE.exists():
        raise ApiError(f"no simulated partner yet ({STATE_FILE} missing); run `join` first")
    return json.loads(STATE_FILE.read_text(encoding="utf-8"))


def birthday_soon() -> str:
    """A birthday three weeks from today (so the companion sees it as upcoming), born in 1997."""
    d = date.today() + timedelta(days=21)
    try:
        return d.replace(year=1997).isoformat()
    except ValueError:  # 29 Feb
        return date(1997, 2, 28).isoformat()


def cmd_join(args: argparse.Namespace) -> None:
    base = args.server_url
    config = call(base, "GET", "/v1/config")
    version = config["consent_statement_version"]

    reg = call(base, "POST", "/v1/auth/register", body={"display_name": args.name})
    token, me = reg["token"], reg["user"]
    print(f"Registered simulated partner '{me['display_name']}' (user {me['id']}).")

    couple = call(base, "POST", "/v1/couple/join", token, {"code": args.invite_code})
    tester = next(u for u in couple["members"] if u["id"] != me["id"])
    print(f"Joined couple {couple['id']} with {tester['display_name']}.")

    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps({"server_url": base, "token": token, "user_id": me["id"],
                                      "name": me["display_name"], "couple_id": couple["id"]}, indent=2),
                          encoding="utf-8")
    print(f"Saved the partner token to {STATE_FILE}.")

    companion = call(base, "GET", "/v1/companions/of-me", token)
    cid = companion["id"]
    print(f"{me['display_name']}'s companion (seen by {tester['display_name']}): {cid}")

    print(f"Granting consent (statement version {version}); the server records these statements:")
    for scope in ("likeness", "voice", "personality"):
        print(f"  - {scope}: {config['consent_statements'][scope]}")
    print("  - ai_training: left OFF")
    companion = call(base, "PUT", f"/v1/companions/{cid}/consent", token,
                     {"likeness": True, "voice": True, "personality": True, "ai_training": False,
                      "statement_version": version})
    print(f"Consents now {companion['consents']}; status {companion['status']}.")

    companion = call(base, "PUT", f"/v1/companions/{cid}/appearance", token, APPEARANCE)
    print(f"Set and approved own appearance; status {companion['status']}.")
    call(base, "PUT", f"/v1/companions/{cid}/persona", token, PERSONA)
    print("Set and approved own persona (nickname 'jaan', playful style, boundaries).")

    for item in WARDROBE:
        call(base, "POST", f"/v1/companions/{cid}/wardrobe", token, item)
    categories = sorted({i["category"] for i in WARDROBE})
    print(f"Added {len(WARDROBE)} wardrobe items ({', '.join(categories)}).")

    occasion = call(base, "POST", "/v1/occasions", token, {
        "kind": "birthday", "title": f"{me['display_name']}'s birthday", "date": birthday_soon(),
        "recurs_yearly": True, "person_user_id": me["id"], "companion_may_initiate": True,
    })
    print(f"Added occasion '{occasion['title']}' on {occasion['date']} (recurs yearly).")
    print(f"Done. {tester['display_name']} can now chat with the companion modelled on {me['display_name']}.")


def cmd_approve(args: argparse.Namespace) -> None:
    base, token = args.server_url, load_state()["token"]
    companion = call(base, "GET", "/v1/companions/of-me", token)
    cid = companion["id"]
    done = 0
    pending = companion.get("pending_appearance")
    if pending:
        call(base, "POST", f"/v1/companions/{cid}/appearance/{pending['id']}/approve", token)
        print(f"Approved appearance version {pending['id']}.")
        done += 1
    if companion.get("pending_persona"):
        call(base, "POST", f"/v1/companions/{cid}/persona/approve", token)
        print("Approved the pending persona.")
        done += 1
    for item in call(base, "GET", f"/v1/companions/{cid}/wardrobe", token):
        if not item["approved"]:
            call(base, "POST", f"/v1/companions/{cid}/wardrobe/{item['id']}/approve", token, {"approved": True})
            print(f"Approved wardrobe item '{item['name']}'.")
            done += 1
    status = call(base, "GET", f"/v1/companions/{cid}", token)["status"]
    print(f"Approved {done} pending item(s). Companion status: {status}.")


def cmd_revoke(args: argparse.Namespace) -> None:
    base, token = args.server_url, load_state()["token"]
    config = call(base, "GET", "/v1/config")
    companion = call(base, "GET", "/v1/companions/of-me", token)
    body = {**companion["consents"], args.scope: False, "statement_version": config["consent_statement_version"]}
    companion = call(base, "PUT", f"/v1/companions/{companion['id']}/consent", token, body)
    print(f"Revoked {args.scope} consent. Consents now {companion['consents']}; status {companion['status']}.")
    if args.scope == "likeness":
        print("Photos, appearance versions, renders and the wardrobe were permanently deleted.")
    elif args.scope == "voice":
        print("Voice samples and any cloned voice were permanently deleted.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Play the partner through the public Her API.")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("join", help="register the partner, join with an invite code, and set up their companion")
    p.add_argument("server_url")
    p.add_argument("invite_code")
    p.add_argument("--name", default="Maya", help="partner display name (default: Maya)")
    p.set_defaults(func=cmd_join)

    p = sub.add_parser("approve", help="approve everything the viewer left pending")
    p.add_argument("server_url")
    p.set_defaults(func=cmd_approve)

    p = sub.add_parser("revoke", help="revoke one consent scope (to test revocation)")
    p.add_argument("server_url")
    p.add_argument("scope", choices=SCOPES)
    p.set_defaults(func=cmd_revoke)

    args = parser.parse_args(argv)
    try:
        args.func(args)
    except ApiError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
