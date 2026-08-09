"""In-memory runtime state for a configured PC."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import TYPE_CHECKING

from homeassistant.core import HomeAssistant
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from .const import (
    DEFAULT_MESSAGE,
    EXTENSION_COOLDOWN_SECONDS,
    EXTENSION_SECONDS,
    INITIAL_GRACE_SECONDS,
    SIGNAL_UPDATE,
)

if TYPE_CHECKING:
    from .screen_monitor import ScreenMonitor


@dataclass(slots=True)
class PcRuntime:
    """State shared by the API and entity platforms."""

    hass: HomeAssistant
    entry_id: str
    device_id: str
    device_name: str
    windows_username: str
    api_key: str
    device_type: str = "windows"
    blocked: bool = False
    message: str = DEFAULT_MESSAGE
    last_seen: datetime | None = None
    active_application: str = ""
    active_window: str = ""
    reported_username: str = ""
    agent_version: str = ""
    ui_status: str = "unknown"
    ui_error: str = ""
    ui_reported_at: datetime | None = None
    portal_error_notified_at: datetime | None = None
    # Passive (cached-only) position reported by the Android agent. May be stale
    # or absent by design — the agent never asks the GPS chip for a fix.
    latitude: float | None = None
    longitude: float | None = None
    gps_accuracy: int | None = None
    location_age_seconds: int | None = None
    location_provider: str = ""
    location_reported_at: datetime | None = None
    backup_status: str = "disabled"
    backup_uploaded: int = 0
    backup_skipped: int = 0
    backup_initial_complete: bool = False
    backup_last_success: datetime | None = None
    backup_error: str = ""
    block_requested_at: datetime | None = None
    extension_used_at: datetime | None = None
    extension_until: datetime | None = None
    # A reward purchase is money-like: one portal submission may spend points
    # only once for the current blocked session.  Keep this durable so a Home
    # Assistant restart cannot turn a slow or failed unlock into a new charge.
    portal_reward_claims: set[str] = field(default_factory=set)
    extra: dict[str, str] = field(default_factory=dict)
    screen_monitor: ScreenMonitor | None = field(default=None, repr=False)
    _store: Store | None = field(default=None, repr=False)

    @property
    def signal(self) -> str:
        return SIGNAL_UPDATE.format(self.entry_id)

    def notify(self) -> None:
        async_dispatcher_send(self.hass, self.signal)

    @property
    def enforce_at(self) -> datetime | None:
        if not self.blocked:
            return None
        requested = self.block_requested_at or dt_util.utcnow()
        deadline = requested + timedelta(seconds=INITIAL_GRACE_SECONDS)
        if self.extension_until is not None and self.extension_until > deadline:
            deadline = self.extension_until
        return deadline

    @property
    def effective_blocked(self) -> bool:
        return self.blocked and self.enforce_at is not None and dt_util.utcnow() >= self.enforce_at

    @property
    def extension_available(self) -> bool:
        return self.extension_used_at is None or dt_util.utcnow() >= (
            self.extension_used_at + timedelta(seconds=EXTENSION_COOLDOWN_SECONDS)
        )

    @property
    def extension_available_at(self) -> datetime | None:
        if self.extension_used_at is None:
            return None
        return self.extension_used_at + timedelta(seconds=EXTENSION_COOLDOWN_SECONDS)

    async def async_load(self) -> None:
        self._store = Store(self.hass, 1, f"{__package__}.{self.device_id}")
        data = await self._store.async_load() or {}
        self.block_requested_at = _parse_datetime(data.get("block_requested_at"))
        self.extension_used_at = _parse_datetime(data.get("extension_used_at"))
        self.extension_until = _parse_datetime(data.get("extension_until"))
        self.portal_reward_claims = {
            str(value) for value in data.get("portal_reward_claims", []) if str(value)
        }

    async def async_save(self) -> None:
        if self._store is None:
            return
        await self._store.async_save(
            {
                "block_requested_at": _format_datetime(self.block_requested_at),
                "extension_used_at": _format_datetime(self.extension_used_at),
                "extension_until": _format_datetime(self.extension_until),
                "portal_reward_claims": sorted(self.portal_reward_claims),
            }
        )

    async def async_set_blocked(self, blocked: bool) -> None:
        if blocked and (not self.blocked or self.block_requested_at is None):
            self.block_requested_at = dt_util.utcnow()
            self.extension_until = None
            self.portal_reward_claims.clear()
        elif not blocked:
            self.block_requested_at = None
            self.extension_until = None
            self.portal_reward_claims.clear()
        self.blocked = blocked
        await self.async_save()
        self.notify()

    async def async_reserve_portal_reward(self, reward_id: str) -> bool:
        """Atomically reserve one reward purchase for this blocked session."""
        if not self.effective_blocked or reward_id in self.portal_reward_claims:
            return False
        self.portal_reward_claims.add(reward_id)
        await self.async_save()
        self.notify()
        return True

    async def async_release_portal_reward(self, reward_id: str) -> None:
        """Release a reservation only when Home Assistant rejected the press."""
        if reward_id not in self.portal_reward_claims:
            return
        self.portal_reward_claims.remove(reward_id)
        await self.async_save()
        self.notify()

    async def async_request_extension(self) -> bool:
        if not self.blocked or not self.extension_available:
            return False
        now = dt_util.utcnow()
        self.extension_used_at = now
        self.extension_until = now + timedelta(seconds=EXTENSION_SECONDS)
        await self.async_save()
        self.notify()
        return True

    def update_activity(self, payload: dict) -> None:
        self.last_seen = dt_util.utcnow()
        self.active_application = str(payload.get("application", ""))[:255]
        self.active_window = str(payload.get("window_title", ""))[:255]
        self.reported_username = str(payload.get("username", ""))[:128]
        self.agent_version = str(payload.get("agent_version", ""))[:64]
        previous_ui_status = self.ui_status
        self.ui_status = str(payload.get("ui_status", "unknown"))[:64] or "unknown"
        self.ui_error = str(payload.get("ui_error", ""))[:255]
        self.ui_reported_at = self.last_seen
        if payload.get("latitude") is not None and payload.get("longitude") is not None:
            try:
                latitude = float(payload["latitude"])
                longitude = float(payload["longitude"])
            except (TypeError, ValueError):
                latitude = longitude = None
            if (
                latitude is not None
                and longitude is not None
                and -90.0 <= latitude <= 90.0
                and -180.0 <= longitude <= 180.0
            ):
                self.latitude = latitude
                self.longitude = longitude
                try:
                    self.gps_accuracy = int(float(payload.get("gps_accuracy", 0)))
                except (TypeError, ValueError):
                    self.gps_accuracy = None
                try:
                    self.location_age_seconds = int(float(payload.get("location_age_seconds", 0)))
                except (TypeError, ValueError):
                    self.location_age_seconds = None
                self.location_provider = str(payload.get("location_provider", ""))[:32]
                self.location_reported_at = self.last_seen
        if (
            self.ui_status != previous_ui_status
            and self.ui_status.startswith("portal_")
            and self.ui_status not in {"portal_initialising", "portal_ready"}
            and (
                self.portal_error_notified_at is None
                or self.last_seen - self.portal_error_notified_at >= timedelta(minutes=15)
            )
        ):
            self.portal_error_notified_at = self.last_seen
            self.hass.bus.async_fire(
                "rowe_pc_blocker_portal_error",
                {
                    "device_id": self.device_id,
                    "device_name": self.device_name,
                    "status": self.ui_status,
                    "message": self.ui_error,
                },
            )
        self.notify()

    def update_backup_status(self, payload: dict) -> None:
        """Store only bounded progress metadata, never media names or object keys."""
        allowed = {
            "disabled",
            "idle",
            "syncing",
            "waiting_for_wifi",
            "permission_required",
            "complete",
            "complete_with_skips",
            "error",
        }
        status = str(payload.get("status", "error"))[:32]
        self.backup_status = status if status in allowed else "error"
        try:
            uploaded = int(payload.get("uploaded", self.backup_uploaded))
        except (TypeError, ValueError):
            uploaded = self.backup_uploaded
        self.backup_uploaded = max(0, min(uploaded, 10_000_000))
        try:
            skipped = int(payload.get("skipped", self.backup_skipped))
        except (TypeError, ValueError):
            skipped = self.backup_skipped
        self.backup_skipped = max(0, min(skipped, 10_000_000))
        self.backup_initial_complete = bool(payload.get("initial_complete", False))
        self.backup_error = str(payload.get("error", ""))[:255]
        last_success = str(payload.get("last_success", "")).strip()
        parsed = dt_util.parse_datetime(last_success) if last_success else None
        if parsed is not None:
            self.backup_last_success = parsed
        self.notify()


def _parse_datetime(value: str | None) -> datetime | None:
    return dt_util.parse_datetime(value) if value else None


def _format_datetime(value: datetime | None) -> str | None:
    return value.isoformat() if value else None
