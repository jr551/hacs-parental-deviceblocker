"""Per-device sensors: what a child's device is doing, and what needs a parent.

Naming and categories follow one rule across the platforms:

* an entity a parent watches or acts on from the device card is left without an
  ``entity_category``;
* an entity that only explains the machinery behind those — agent internals,
  the optional screen monitor's detail, the app list used to build an
  allowlist — is ``diagnostic``.

Telemetry that only an agent check-in refreshes derives from
``AgentTelemetryEntity``, so a silent agent shows as unavailable instead of
leaving a stale reading on the dashboard.
"""

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorStateClass,
)
from homeassistant.const import EntityCategory
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.util import dt as dt_util

from .agent_status import DEGRADED_UI_STATUSES, is_portal_error_status
from .const import DEVICE_TYPE_ANDROID, DOMAIN, SIGNAL_PENDING
from .entity import AgentTelemetryEntity, ParentalDeviceEntity
from .evidence import evidence_expires_at, evidence_file
from .notifications import recent_items
from .pending import awaiting_requests, claim_pending_entities, unblock_requests

# The states the challenge sensor reports, and the concern levels the screen
# monitor reports. Both are enum sensors, so Home Assistant needs the full set
# up front.
CHALLENGE_STATES = ["none", "pending", "accepted"]
RISK_LEVELS = ["none", "low", "moderate", "high", "critical"]


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    entities: list[SensorEntity] = [
        ActiveApplicationSensor(runtime),
        ActiveWindowSensor(runtime),
        ScreenAssessmentSensor(runtime),
        ScreenSafetySensor(runtime),
        DailyActivitySummarySensor(runtime),
        WeeklyActivitySummarySensor(runtime),
    ]
    if runtime.device_type == DEVICE_TYPE_ANDROID:
        entities += [
            EnforcementStatusSensor(runtime),
            InstalledAppsSensor(runtime),
            UnblockRequestsSensor(runtime),
            ChallengeSensor(runtime),
            NotificationsSensor(runtime),
        ]
    else:
        # A PC reports the points-kiosk state in the same agent field a phone
        # uses for Accessibility state, so only the one that applies exists.
        entities.append(PortalStatusSensor(runtime))
    async_add_entities(entities)
    # The pending-setups sensor is integration-wide, so only the entry that
    # owns the pending entities creates it.
    if claim_pending_entities(hass, entry.entry_id):
        async_add_entities([PendingSetupsSensor(hass)])


class ActiveApplicationSensor(AgentTelemetryEntity, SensorEntity):
    """The foreground application the agent last reported."""

    _attr_name = "Active application"
    _attr_icon = "mdi:application"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_active_application"

    @property
    def native_value(self) -> str:
        return self.runtime.active_application or "Idle"

    @property
    def extra_state_attributes(self) -> dict:
        return {
            # The account the agent reported the activity under. The agent
            # version is a device fact and lives in the device firmware
            # version instead.
            "username": self.runtime.reported_username or None,
        }


class ActiveWindowSensor(AgentTelemetryEntity, SensorEntity):
    """The window title behind the active application."""

    _attr_name = "Active window"
    _attr_icon = "mdi:monitor-eye"
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_active_window"

    @property
    def native_value(self) -> str:
        return self.runtime.active_window or "Idle"


class PortalStatusSensor(AgentTelemetryEntity, SensorEntity):
    """The Windows points-kiosk state reported by the activity app."""

    _attr_name = "Points portal status"
    _attr_icon = "mdi:web-check"
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_portal_status"

    @property
    def native_value(self) -> str:
        return self.runtime.ui_status

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "detail": self.runtime.ui_error or None,
            "reported_at": (
                self.runtime.ui_reported_at.isoformat()
                if self.runtime.ui_reported_at
                else None
            ),
            "healthy": not is_portal_error_status(self.runtime.ui_status),
        }


class EnforcementStatusSensor(AgentTelemetryEntity, SensorEntity):
    """The Android Accessibility and fail-open state reported by the phone."""

    _attr_name = "Enforcement status"
    _attr_icon = "mdi:shield-alert"
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_enforcement_status"

    @property
    def native_value(self) -> str:
        return self.runtime.ui_status or "unknown"

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "detail": self.runtime.ui_error or None,
            "reported_at": (
                self.runtime.ui_reported_at.isoformat()
                if self.runtime.ui_reported_at
                else None
            ),
            # One flag for every status that means the block is not being
            # enforced, matching the rowe_pc_blocker_enforcement_degraded
            # event. The state names which one it is.
            "degraded": self.runtime.ui_status in DEGRADED_UI_STATUSES,
        }


class _ScreenMonitorSensor(ParentalDeviceEntity, SensorEntity):
    """Shared availability for the optional VNC screen-assessment sensors.

    These read the screen monitor rather than the device agent, so they follow
    the monitor's own configured state: with the feature off there is nothing
    to report, and the entity says so by being unavailable. The Check screen
    now button uses the same rule.
    """

    @property
    def available(self) -> bool:
        monitor = self.runtime.screen_monitor
        return monitor is not None and monitor.settings.configured


class ScreenAssessmentSensor(_ScreenMonitorSensor):
    """Latest text assessment; raw screenshots are never retained."""

    _attr_name = "Screen assessment"
    _attr_icon = "mdi:image-search"
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_screen_assessment"

    @property
    def native_value(self) -> str:
        monitor = self.runtime.screen_monitor
        if monitor.last_summary:
            return monitor.last_summary[:255]
        return monitor.status.replace("_", " ").title()

    @property
    def extra_state_attributes(self) -> dict:
        monitor = self.runtime.screen_monitor
        return {
            "status": monitor.status,
            "full_summary": monitor.last_summary or None,
            "last_error": monitor.last_error or None,
            "last_checked_at": (
                monitor.last_checked_at.isoformat() if monitor.last_checked_at else None
            ),
            "last_attempted_at": (
                monitor.last_attempted_at.isoformat()
                if monitor.last_attempted_at
                else None
            ),
            "last_trigger": monitor.last_trigger or None,
            "category": monitor.last_category,
            "risk_score": monitor.last_risk_score,
            "risk_level": monitor.last_risk_level,
            "reasons": list(monitor.last_reasons),
            "next_check_at": (
                monitor.next_check_at.isoformat() if monitor.next_check_at else None
            ),
            "schedule_mode": monitor.settings.schedule_mode,
            "images_retained": False,
        }


class ScreenSafetySensor(_ScreenMonitorSensor):
    """Latest structured concern level for automation triggers."""

    _attr_name = "Screen safety"
    _attr_icon = "mdi:shield-check"
    _attr_device_class = SensorDeviceClass.ENUM
    _attr_options = RISK_LEVELS

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_screen_safety"

    @property
    def native_value(self) -> str:
        monitor = self.runtime.screen_monitor
        return monitor.last_risk_level if monitor is not None else "none"

    @property
    def extra_state_attributes(self) -> dict:
        monitor = self.runtime.screen_monitor
        return {
            "risk_score": monitor.last_risk_score,
            "category": monitor.last_category,
            "reasons": list(monitor.last_reasons),
            "summary": monitor.last_summary or None,
            "last_checked_at": (
                monitor.last_checked_at.isoformat() if monitor.last_checked_at else None
            ),
            "high_alert_threshold": 4,
            "images_retained": False,
        }


class _ActivitySummarySensor(_ScreenMonitorSensor):
    """Shared deterministic text-only activity report sensor."""

    period = "today"

    @property
    def native_value(self) -> str:
        return self.runtime.screen_monitor.activity_report(self.period)[
            "full_summary"
        ][:255]

    @property
    def extra_state_attributes(self) -> dict:
        report = self.runtime.screen_monitor.activity_report(self.period)
        return {
            **report,
            "text_retention_days": 14,
            "images_retained": False,
        }


class DailyActivitySummarySensor(_ActivitySummarySensor):
    _attr_name = "Daily activity summary"
    _attr_icon = "mdi:calendar-today"
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    period = "today"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_daily_activity_summary"


class WeeklyActivitySummarySensor(_ActivitySummarySensor):
    _attr_name = "Weekly activity summary"
    _attr_icon = "mdi:calendar-week"
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    period = "week"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_weekly_activity_summary"


class InstalledAppsSensor(AgentTelemetryEntity, SensorEntity):
    """The launchable apps the phone last reported, for choosing an allowlist."""

    _attr_name = "Installed apps"
    _attr_icon = "mdi:apps"
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _attr_state_class = SensorStateClass.MEASUREMENT

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_installed_apps"

    @property
    def native_value(self) -> int:
        return len(self.runtime.installed_apps)

    @property
    def extra_state_attributes(self) -> dict:
        return {"apps": [dict(app) for app in self.runtime.installed_apps]}


class UnblockRequestsSensor(ParentalDeviceEntity, SensorEntity):
    """The apps this phone has asked a parent to allow.

    The phone polls its state every minute, so an approved app clears the block
    screen by itself; this sensor is how a parent sees what is waiting. It is
    Home Assistant state rather than agent telemetry, so it stays available
    while the phone is offline.
    """

    _attr_name = "Unblock requests"
    _attr_icon = "mdi:lock-open-alert"
    _attr_state_class = SensorStateClass.MEASUREMENT

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_unblock_requests"

    @property
    def native_value(self) -> int:
        return len(unblock_requests(self.hass, self.runtime.device_id))

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "requests": [
                {
                    "request_id": request.request_id,
                    "package": request.package,
                    "label": request.label,
                    "requested_at": request.requested_at.isoformat(),
                }
                for request in unblock_requests(self.hass, self.runtime.device_id)
            ]
        }

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, SIGNAL_PENDING, self.async_write_ha_state
            )
        )


class ChallengeSensor(ParentalDeviceEntity, SensorEntity):
    """The chore challenge this phone must answer before it unlocks.

    ``none`` until a parent sends a phrase, ``pending`` while the child still
    has to type it back, and ``accepted`` once they have. The evidence
    attributes come from the stored photo, so they survive a restart. Like the
    unblock requests, this is Home Assistant state and stays available while
    the phone is offline.
    """

    _attr_name = "Challenge"
    _attr_icon = "mdi:form-textbox"
    _attr_device_class = SensorDeviceClass.ENUM
    _attr_options = CHALLENGE_STATES

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_challenge"

    @property
    def native_value(self) -> str:
        return self.runtime.challenge_state

    @property
    def extra_state_attributes(self) -> dict:
        path = evidence_file(
            self.hass, self.runtime.device_id, self.runtime.challenge_id
        )
        return {
            "phrase": self.runtime.challenge_phrase or None,
            "sent_at": (
                self.runtime.challenge_sent_at.isoformat()
                if self.runtime.challenge_sent_at
                else None
            ),
            "accepted_at": (
                self.runtime.challenge_accepted_at.isoformat()
                if self.runtime.challenge_accepted_at
                else None
            ),
            "evidence_at": (
                dt_util.utc_from_timestamp(path.stat().st_mtime).isoformat()
                if path is not None
                else None
            ),
            "evidence_expires_at": (
                evidence_expires_at(path).isoformat() if path is not None else None
            ),
        }


class NotificationsSensor(ParentalDeviceEntity, SensorEntity):
    """The phone's notifications for the latest day it reported.

    The phone posts one dump a day; this sensor is the parent-facing summary:
    the day's count, which apps were noisiest, and the most recent items. The
    dump is trimmed at ingest, so the attributes stay inside the recorder's
    size limit. Home Assistant state rather than agent telemetry, so it stays
    available while the phone is offline.
    """

    _attr_name = "Notifications"
    _attr_icon = "mdi:bell-ring-outline"
    _attr_state_class = SensorStateClass.MEASUREMENT

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_notifications"

    @property
    def native_value(self) -> int | None:
        dump = self.runtime.notifications
        return dump.count if dump is not None else None

    @property
    def extra_state_attributes(self) -> dict:
        dump = self.runtime.notifications
        if dump is None:
            return {
                "date": None,
                "truncated": None,
                "apps": [],
                "items": [],
                "received_at": None,
            }
        return {
            "date": dump.date,
            "truncated": dump.truncated,
            # The summary covers the whole day, so it is computed once at
            # ingest rather than from the trimmed items.
            "apps": [dict(app) for app in dump.apps],
            "items": [item.as_dict() for item in recent_items(dump.items)],
            "received_at": dump.received_at.isoformat(),
        }


class PendingSetupsSensor(SensorEntity):
    """How many phones are waiting for a parent to approve their setup.

    Integration-wide rather than per device: the name is spelled so that Home
    Assistant derives the documented entity id
    ``sensor.rowe_pc_blocker_pending_setups`` from it.
    """

    _attr_has_entity_name = False
    _attr_name = "Rowe PC blocker pending setups"
    _attr_icon = "mdi:cellphone-arrow-down"
    _attr_state_class = SensorStateClass.MEASUREMENT
    _attr_unique_id = f"{DOMAIN}_pending_setups"

    def __init__(self, hass) -> None:
        self.hass = hass

    @property
    def native_value(self) -> int:
        return len(awaiting_requests(self.hass))

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "requests": [
                {
                    "request_id": request.request_id,
                    "child_name": request.child_name,
                    "requested_at": request.requested_at.isoformat(),
                }
                for request in awaiting_requests(self.hass)
            ]
        }

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, SIGNAL_PENDING, self.async_write_ha_state
            )
        )
