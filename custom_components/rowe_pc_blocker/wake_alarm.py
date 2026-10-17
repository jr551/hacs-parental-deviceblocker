"""Wake-alarm options shared by the config flow and the device state poll.

This module has no Home Assistant imports so the schedule rules can be tested
without a running Home Assistant instance. Volume is a percentage from 0 to 100
of the phone's alarm-stream maximum. Bit 0 of ``days`` is Monday.
"""

from __future__ import annotations

from typing import Any, Mapping

CONF_WAKE_ALARM_ENABLED = "wake_alarm_enabled"
CONF_WAKE_ALARM_TIME = "wake_alarm_time"
CONF_WAKE_ALARM_DAYS = "wake_alarm_days"
CONF_WAKE_ALARM_VOLUME = "wake_alarm_volume"

WAKE_DAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
DEFAULT_WAKE_DAYS = ("mon", "tue", "wed", "thu", "fri")
DEFAULT_WAKE_TIME = "07:00"
DEFAULT_WAKE_VOLUME = 100


def normalize_wake_alarm_form(raw: Mapping[str, Any]) -> tuple[dict[str, Any], dict[str, str]]:
    """Validate one options-form submission and return cleaned alarm fields."""

    errors: dict[str, str] = {}
    enabled = bool(raw.get(CONF_WAKE_ALARM_ENABLED, False))
    clock = _clock(raw.get(CONF_WAKE_ALARM_TIME, DEFAULT_WAKE_TIME))
    if clock is None:
        errors[CONF_WAKE_ALARM_TIME] = "invalid_wake_time"
        clock = (7, 0)
    days = _as_days(raw.get(CONF_WAKE_ALARM_DAYS, list(DEFAULT_WAKE_DAYS)))
    if any(day not in WAKE_DAYS for day in days):
        errors[CONF_WAKE_ALARM_DAYS] = "invalid_wake_days"
    ordered = [day for day in WAKE_DAYS if day in days]
    if enabled and not ordered and CONF_WAKE_ALARM_DAYS not in errors:
        errors[CONF_WAKE_ALARM_DAYS] = "wake_days_required"
    volume = _volume_exact(raw.get(CONF_WAKE_ALARM_VOLUME, DEFAULT_WAKE_VOLUME))
    if volume is None:
        errors[CONF_WAKE_ALARM_VOLUME] = "invalid_wake_volume"
        volume = DEFAULT_WAKE_VOLUME
    cleaned = {
        CONF_WAKE_ALARM_ENABLED: enabled,
        CONF_WAKE_ALARM_TIME: f"{clock[0]:02d}:{clock[1]:02d}",
        CONF_WAKE_ALARM_DAYS: ordered,
        CONF_WAKE_ALARM_VOLUME: volume,
    }
    return cleaned, errors


def wake_alarm_payload(options: Mapping[str, Any] | None) -> dict[str, Any]:
    """Return the alarm object included on the device state poll."""

    options = options or {}
    clock = _clock(options.get(CONF_WAKE_ALARM_TIME, DEFAULT_WAKE_TIME)) or (7, 0)
    if CONF_WAKE_ALARM_DAYS in options and options.get(CONF_WAKE_ALARM_DAYS) is not None:
        day_bits = _day_bits(options.get(CONF_WAKE_ALARM_DAYS))
    else:
        day_bits = _day_bits(DEFAULT_WAKE_DAYS)
    return {
        "enabled": bool(options.get(CONF_WAKE_ALARM_ENABLED, False)),
        "hour": clock[0],
        "minute": clock[1],
        "days": day_bits,
        "volume": _volume_clamped(options.get(CONF_WAKE_ALARM_VOLUME, DEFAULT_WAKE_VOLUME)),
    }


def _clock(value: Any) -> tuple[int, int] | None:
    text = str(value or "").strip()
    parts = text.split(":")
    if len(parts) not in (2, 3):
        return None
    try:
        hour = int(parts[0])
        minute = int(parts[1])
    except ValueError:
        return None
    if hour < 0 or hour > 23 or minute < 0 or minute > 59:
        return None
    return hour, minute


def _as_days(value: Any) -> list[str]:
    if isinstance(value, str):
        parts = value.split(",")
    elif isinstance(value, (list, tuple)):
        parts = list(value)
    else:
        return []
    return [str(part).strip().lower() for part in parts if str(part).strip()]


def _day_bits(value: Any) -> int:
    bits = 0
    for day in _as_days(value):
        if day in WAKE_DAYS:
            bits |= 1 << WAKE_DAYS.index(day)
    return bits


def _volume_exact(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if abs(number - round(number)) > 1e-6:
        return None
    volume = int(round(number))
    if volume < 0 or volume > 100:
        return None
    return volume


def _volume_clamped(value: Any) -> int:
    exact = _volume_exact(value)
    if exact is None:
        return DEFAULT_WAKE_VOLUME
    return exact
