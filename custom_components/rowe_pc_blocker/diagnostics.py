"""Diagnostics support for Parental Device Blocker."""

from __future__ import annotations

from typing import Any

from homeassistant.components.diagnostics import async_redact_data
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant

from .const import CONF_API_KEY, CONF_KILO_API_KEY, CONF_PARENT_PIN, CONF_VNC_PASSWORD, DOMAIN

REDACT = {
    CONF_API_KEY,
    CONF_VNC_PASSWORD,
    CONF_KILO_API_KEY,
    CONF_PARENT_PIN,
    "s3_access_key",
    "s3_secret_key",
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
    if runtime is not None:
        payload["runtime"] = {
            "device_id": runtime.device_id,
            "device_type": runtime.device_type,
            "blocked": runtime.blocked,
            "effective_blocked": runtime.effective_blocked,
            "last_seen": runtime.last_seen.isoformat() if runtime.last_seen else None,
            "agent_version": runtime.agent_version or None,
            "ui_status": runtime.ui_status,
        }
    return payload
