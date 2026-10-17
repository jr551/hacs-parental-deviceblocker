"""Pure helpers for agent UI and Android enforcement status."""

from __future__ import annotations

from datetime import datetime

DEGRADED_UI_STATUSES = frozenset(
    {
        "fail_open",
        "accessibility_disabled",
        "accessibility_interrupted",
    }
)

# One stale episode: alert once when the agent goes silent, once when it
# returns. A still-silent agent must not repeat the stale event.
AGENT_STALE = "stale"
AGENT_RECOVERED = "recovered"

# Statuses that mean the points portal is not broken. `idle` is what the
# Windows activity app reports while the kiosk is hidden; older builds reported
# a sticky `starting` value in that same situation.
HEALTHY_PORTAL_STATUSES = frozenset(
    {
        "portal_initialising",
        "portal_ready",
        "portal_idle",
        "portal_disabled",
    }
)


def resolve_ui_status(previous: str, payload: dict) -> str:
    """Keep the last status when an agent omits ``ui_status``.

    Android heartbeats used to omit the field, and Home Assistant then stored
    ``unknown``, which hid a Windows portal status on any shared code path and
    made a phone look unclassified.
    """
    if "ui_status" not in payload:
        return previous or "unknown"
    status = str(payload.get("ui_status") or "")[:64]
    return status or "unknown"


def resolve_ui_error(previous: str, payload: dict) -> str:
    if "ui_status" not in payload and "ui_error" not in payload:
        return previous
    return str(payload.get("ui_error", ""))[:255]


def is_portal_error_status(status: str) -> bool:
    return status.startswith("portal_") and status not in HEALTHY_PORTAL_STATUSES


def agent_is_online(
    last_seen: datetime | None, now: datetime, timeout_seconds: int
) -> bool:
    """True when the agent checked in within the timeout."""
    if last_seen is None:
        return False
    return (now - last_seen).total_seconds() < timeout_seconds


def agent_stale_transition(
    was_stale: bool, online: bool, seen: bool
) -> tuple[bool, str | None]:
    """Track one stale episode across checks.

    Returns the new stale flag and, when this check crosses a boundary, either
    ``AGENT_STALE`` or ``AGENT_RECOVERED``. A device that has never checked in
    is not stale: there is no episode to report.
    """
    if online:
        return (False, AGENT_RECOVERED) if was_stale else (False, None)
    if seen and not was_stale:
        return True, AGENT_STALE
    return was_stale, None


def should_notify_enforcement_degraded(
    previous: str,
    current: str,
    *,
    block_requested: bool,
    previous_block_requested: bool,
    last_notified_at: datetime | None,
    now: datetime,
    cooldown_seconds: int = 15 * 60,
) -> bool:
    """Alert when Android enforcement is no longer doing what a parent asked.

    Accessibility being switched off is always parent-visible. A fail-open
    alert is limited to an active block request so a routine network blip while
    the phone is allowed does not page anyone.
    """
    if current not in DEGRADED_UI_STATUSES:
        return False
    if current == "fail_open" and not block_requested:
        return False
    status_changed = current != previous
    block_escalated = (
        current == "fail_open"
        and block_requested
        and not previous_block_requested
    )
    if not status_changed and not block_escalated:
        return False
    if (
        last_notified_at is not None
        and (now - last_notified_at).total_seconds() < cooldown_seconds
    ):
        return False
    return True
