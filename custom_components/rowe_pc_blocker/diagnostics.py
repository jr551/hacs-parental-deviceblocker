"""Diagnostics support for Parental Device Blocker.

Everything a support case needs about one device, with the secrets redacted:
the device key, the VNC password, the provider key, and the parent PIN. The
challenge phrase and the stored evidence path are included — they are what the
child was asked and what a parent can look at, not credentials.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from homeassistant.components.diagnostics import async_redact_data
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant

from .apps import parse_allowed_apps
from .const import (
    CONF_API_KEY,
    CONF_KILO_API_KEY,
    CONF_PARENT_PIN,
    CONF_VNC_PASSWORD,
    DOMAIN,
)
from .evidence import evidence_expires_at, evidence_file
from .pending import unblock_requests

REDACT = {
    CONF_API_KEY,
    CONF_VNC_PASSWORD,
    CONF_KILO_API_KEY,
    CONF_PARENT_PIN,
}


async def async_get_config_entry_diagnostics(
    hass: HomeAssistant, entry: ConfigEntry
) -> dict[str, Any]:
    """Return redacted diagnostics for support."""
    runtime = hass.data.get(DOMAIN, {}).get(entry.entry_id)
    payload: dict[str, Any] = {
        "entry": async_redact_data(
            {
                "title": entry.title,
                "version": entry.version,
                "data": dict(entry.data),
                "options": dict(entry.options),
            },
            REDACT,
        ),
    }
    if runtime is None:
        return payload
    payload["runtime"] = {
        "device_id": runtime.device_id,
        "device_name": runtime.device_name,
        "device_type": runtime.device_type,
        "windows_username": runtime.windows_username,
        "blocked": runtime.blocked,
        "effective_blocked": runtime.effective_blocked,
        "block_reason": runtime.block_reason,
        "block_requested_at": _iso(runtime.block_requested_at),
        "enforce_at": _iso(runtime.enforce_at),
        "extension_used_at": _iso(runtime.extension_used_at),
        "extension_until": _iso(runtime.extension_until),
        "last_seen": _iso(runtime.last_seen),
        "agent_stale": runtime.agent_stale,
        "agent_version": runtime.agent_version or None,
        "ui_status": runtime.ui_status,
        "ui_error": runtime.ui_error or None,
        "allowed_apps": parse_allowed_apps(runtime.allowed_apps),
        "installed_apps": [dict(app) for app in runtime.installed_apps],
        "challenge": _challenge(hass, runtime),
        "unblock_requests": [
            {
                "request_id": request.request_id,
                "package": request.package,
                "label": request.label,
                "requested_at": _iso(request.requested_at),
            }
            for request in unblock_requests(hass, runtime.device_id)
        ],
        "location": {
            "latitude": runtime.latitude,
            "longitude": runtime.longitude,
            "accuracy_metres": runtime.gps_accuracy,
            "age_seconds": runtime.location_age_seconds,
            "provider": runtime.location_provider or None,
            "reported_at": _iso(runtime.location_reported_at),
        },
        "notifications": _notifications(runtime),
        "screen_monitor": _screen_monitor(runtime),
    }
    return payload


def _challenge(hass: HomeAssistant, runtime) -> dict | None:
    """The active chore challenge, and whether its photo is still stored."""
    if not runtime.challenge_id:
        return None
    path = evidence_file(hass, runtime.device_id, runtime.challenge_id)
    return {
        "id": runtime.challenge_id,
        "state": runtime.challenge_state,
        "phrase": runtime.challenge_phrase,
        "sent_at": _iso(runtime.challenge_sent_at),
        "accepted_at": _iso(runtime.challenge_accepted_at),
        "evidence": {
            "stored": path is not None,
            "expires_at": _iso(evidence_expires_at(path)) if path else None,
        },
    }


def _notifications(runtime) -> dict | None:
    """The notification dump's shape, without the child's notification text.

    A notification's title and body are a child's private data, so a support
    bundle carries only the day, the counts, and the apps they came from.
    """
    dump = runtime.notifications
    if dump is None:
        return None
    return {
        "date": dump.date,
        "count": dump.count,
        "truncated": dump.truncated,
        "received_at": dump.received_at.isoformat(),
        "apps": [dict(app) for app in dump.apps],
        "items_stored": len(dump.items),
        "text_redacted": True,
    }


def _screen_monitor(runtime) -> dict | None:
    """The optional screen monitor's state, without the assessed text.

    The summary describes what was on the child's screen, so it stays out of a
    support bundle; the structured fields are what a diagnosis needs.
    """
    monitor = runtime.screen_monitor
    if monitor is None:
        return None
    return {
        "status": monitor.status,
        "configured": monitor.settings.configured,
        "schedule_mode": monitor.settings.schedule_mode,
        "last_checked_at": _iso(monitor.last_checked_at),
        "last_attempted_at": _iso(monitor.last_attempted_at),
        "next_check_at": _iso(monitor.next_check_at),
        "last_trigger": monitor.last_trigger or None,
        "last_error": monitor.last_error or None,
        "risk_score": monitor.last_risk_score,
        "risk_level": monitor.last_risk_level,
        "category": monitor.last_category,
        "images_retained": False,
    }


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None
