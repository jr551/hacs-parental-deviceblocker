"""On-demand location: a parent asks, the phone answers once.

The agent no longer reports position on every heartbeat. A parent presses
Request location, the request rides the phone's next state poll, and the phone
posts one fix back. The request expires, so a phone that was offline cannot
answer an hour later and have the answer look current.

This module has no Home Assistant imports so the rules can be tested without a
running Home Assistant instance.
"""

from __future__ import annotations

from datetime import datetime, timedelta

# A request is only useful while a parent is still waiting for it.
LOCATION_REQUEST_TTL_SECONDS = 600

MAX_PROVIDER_LENGTH = 32

# The phone says how old the fix it sent already was. A fix older than this is
# still stored — the age is what tells a parent not to trust it — but a
# nonsense value is dropped rather than shown.
MAX_FIX_AGE_SECONDS = 7 * 24 * 60 * 60


def request_is_live(
    requested_at: datetime | None,
    now: datetime,
    ttl_seconds: int = LOCATION_REQUEST_TTL_SECONDS,
) -> bool:
    """True while a request is still worth answering."""
    if requested_at is None:
        return False
    return now - requested_at < timedelta(seconds=ttl_seconds)


def parse_coordinates(payload: dict) -> tuple[float, float] | None:
    """The latitude and longitude, or None when either is unusable."""
    try:
        latitude = float(payload["latitude"])
        longitude = float(payload["longitude"])
    except (KeyError, TypeError, ValueError):
        return None
    if not -90.0 <= latitude <= 90.0 or not -180.0 <= longitude <= 180.0:
        return None
    return latitude, longitude


def location_answer_refusal(
    payload: dict,
    request_id: str,
    requested_at: datetime | None,
    now: datetime,
) -> tuple[int, dict] | None:
    """Why this answer cannot be stored, or None when it can.

    A request id that is not the current one, or one that has expired, is a
    404: a late answer must not overwrite a newer request, and the phone is
    told the same thing either way.
    """
    if not request_id or str(payload.get("request_id", "")) != request_id:
        return 404, {"error": "That location request has expired."}
    if not request_is_live(requested_at, now):
        return 404, {"error": "That location request has expired."}
    if parse_coordinates(payload) is None:
        return 400, {
            "error": (
                "Send a latitude between -90 and 90 and a longitude "
                "between -180 and 180."
            )
        }
    return None


def parse_fix_age(payload: dict) -> int | None:
    """How old the phone said the fix was, bounded, or None when unusable."""
    try:
        age = int(float(payload.get("fix_age_seconds", 0)))
    except (TypeError, ValueError):
        return None
    if age < 0 or age > MAX_FIX_AGE_SECONDS:
        return None
    return age


def parse_provider(payload: dict) -> str:
    """The provider the phone named, bounded."""
    return str(payload.get("provider", "")).strip()[:MAX_PROVIDER_LENGTH]


def parse_accuracy(payload: dict) -> float | None:
    """The reported accuracy in metres, or None when unusable."""
    try:
        accuracy = float(payload.get("accuracy", 0))
    except (TypeError, ValueError):
        return None
    return accuracy if accuracy >= 0 else None
