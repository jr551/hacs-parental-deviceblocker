"""The daily notification dump a phone posts for a parent to review.

The phone captures notifications and posts one dump per day. Home Assistant
keeps only the latest dump per device, trimmed at ingest to what the sensor
shows: a per-app summary of the whole day plus the most recent items. Nothing
here grows with the child's notification history, and the runtime store stays
small enough to be rewritten whenever the block state changes.

The caps are not just tidiness. Home Assistant's recorder refuses to store a
state whose attributes exceed 16 KiB (``MAX_STATE_ATTRS_BYTES``), so a dump is
bounded by count and by serialised size rather than by trust in the phone.

This module has no Home Assistant imports so the parsing and summarising rules
can be tested without a running Home Assistant instance.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Iterable

# One dump is a day of one phone's notifications. The cap keeps a runaway
# capture from filling the config directory.
MAX_DUMP_BYTES = 256 * 1024

# What the sensor shows. The count cap is the documented one; the byte budget
# is what actually keeps the state attributes inside the recorder's limit,
# since one full-text item is a few hundred bytes.
MAX_SENSOR_ITEMS = 100
MAX_ITEMS_ATTRIBUTE_BYTES = 10 * 1024
MAX_SENSOR_APPS = 40

# Per-field bounds, so one notification cannot dominate the payload.
MAX_PACKAGE_LENGTH = 64
MAX_APP_LENGTH = 64
MAX_TITLE_LENGTH = 120
MAX_TEXT_LENGTH = 200

DATE_FORMAT = "%Y-%m-%d"
DATE_LENGTH = 10

_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


@dataclass(slots=True)
class NotificationItem:
    """One captured notification, bounded and safe to store."""

    package: str
    app: str
    title: str
    text: str
    posted_at: datetime | None

    def as_dict(self) -> dict:
        return {
            "package": self.package,
            "app": self.app,
            "title": self.title,
            "text": self.text,
            "posted_at": self.posted_at.isoformat() if self.posted_at else None,
        }

    @classmethod
    def from_dict(cls, value) -> "NotificationItem | None":
        if not isinstance(value, dict):
            return None
        return cls(
            package=_identifier(value.get("package"), MAX_PACKAGE_LENGTH),
            app=_identifier(value.get("app"), MAX_APP_LENGTH),
            title=_bounded(value.get("title"), MAX_TITLE_LENGTH),
            text=_bounded(value.get("text"), MAX_TEXT_LENGTH),
            posted_at=parse_posted_at(value.get("posted_at")),
        )


@dataclass(slots=True)
class NotificationDump:
    """The latest dump for one device, already trimmed to what is shown."""

    date: str
    count: int
    truncated: bool
    apps: list[dict]
    items: list[NotificationItem]
    received_at: datetime

    def as_dict(self) -> dict:
        return {
            "date": self.date,
            "count": self.count,
            "truncated": self.truncated,
            "apps": [dict(app) for app in self.apps],
            "items": [item.as_dict() for item in self.items],
            "received_at": self.received_at.isoformat(),
        }

    @classmethod
    def from_dict(cls, value) -> "NotificationDump | None":
        """Rebuild a stored dump, or None when the stored value is unusable."""
        if not isinstance(value, dict) or not valid_date(value.get("date")):
            return None
        items = [NotificationItem.from_dict(item) for item in value.get("items", [])]
        return cls(
            date=str(value["date"]),
            count=_count(value.get("count")),
            truncated=bool(value.get("truncated", False)),
            apps=[dict(app) for app in value.get("apps", []) if isinstance(app, dict)],
            items=[item for item in items if item is not None],
            received_at=parse_posted_at(value.get("received_at"))
            or datetime.now(timezone.utc),
        )


def valid_date(value) -> bool:
    """True when the value is a ``YYYY-MM-DD`` date string.

    The length is checked as well as the format so the stored dates stay
    comparable as plain strings.
    """
    if not isinstance(value, str) or len(value) != DATE_LENGTH:
        return False
    try:
        datetime.strptime(value, DATE_FORMAT)
    except ValueError:
        return False
    return True


def parse_posted_at(value) -> datetime | None:
    """Parse the phone's timestamp, assuming UTC when it carries no offset."""
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed


def dump_refusal(payload: dict) -> tuple[int, dict] | None:
    """Why this dump cannot be stored, or None when it can."""
    if not valid_date(payload.get("date")):
        return 400, {"error": "Send the dump with a date as YYYY-MM-DD."}
    return None


def is_newer(date: str, stored_date: str | None) -> bool:
    """True when this dump is for the stored day or a newer one.

    A late retry must not overwrite a newer day, and a phone re-sending the
    same day after a dropped response must still be accepted.
    """
    if not stored_date:
        return True
    return date >= stored_date


def parse_dump(payload: dict, received_at: datetime) -> NotificationDump:
    """Bound one accepted dump to what the sensor can show.

    The app summary covers the whole day; the items are the most recent that
    fit the sensor's caps, so a busy day cannot push the state attributes past
    the recorder's limit.
    """
    items = [NotificationItem.from_dict(item) for item in _items(payload)]
    parsed = [item for item in items if item is not None]
    return NotificationDump(
        date=str(payload["date"]),
        count=_count(payload.get("count")),
        truncated=bool(payload.get("truncated", False)),
        apps=app_summary(parsed),
        items=recent_items(parsed),
        received_at=received_at,
    )


def app_summary(items: Iterable[NotificationItem]) -> list[dict]:
    """Per-app counts for the whole day, most-used first.

    Ties break on the package name so the order is stable between reads.
    """
    counts: dict[str, dict] = {}
    for item in items:
        entry = counts.setdefault(
            item.package, {"package": item.package, "app": item.app, "count": 0}
        )
        entry["count"] += 1
        if item.app and not entry["app"]:
            entry["app"] = item.app
    ordered = sorted(
        counts.values(), key=lambda entry: (-entry["count"], entry["package"])
    )
    return ordered[:MAX_SENSOR_APPS]


def recent_items(
    items: Iterable[NotificationItem],
    limit: int = MAX_SENSOR_ITEMS,
    budget: int = MAX_ITEMS_ATTRIBUTE_BYTES,
) -> list[NotificationItem]:
    """The most recent items, newest first, inside the sensor's caps.

    Items without a usable timestamp sort last, and the list stops at the
    first item that would take the serialised attribute past its budget.
    """
    ordered = sorted(items, key=lambda item: item.posted_at or _EPOCH, reverse=True)
    kept: list[NotificationItem] = []
    used = 0
    for item in ordered[:limit]:
        # The escaped form is ASCII, so its length is its byte length — the
        # same measure the recorder applies to the whole attribute dict.
        size = len(json.dumps(item.as_dict()))
        if used + size > budget:
            break
        used += size
        kept.append(item)
    return kept


def _items(payload: dict) -> list:
    items = payload.get("items", [])
    return items if isinstance(items, list) else []


def _count(value) -> int:
    try:
        count = int(value)
    except (TypeError, ValueError):
        return 0
    return max(count, 0)


def _bounded(value, limit: int) -> str:
    return "" if value is None else str(value)[:limit]


def _identifier(value, limit: int) -> str:
    return "" if value is None else str(value).strip()[:limit]
