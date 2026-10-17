"""PC activity sensors."""

from homeassistant.components.sensor import SensorEntity
from homeassistant.helpers.dispatcher import async_dispatcher_connect

from .const import DEVICE_TYPE_ANDROID, DOMAIN, SIGNAL_PENDING
from .entity import ParentalDeviceEntity
from .pending import awaiting_requests, claim_pending_entities


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    entities = [
        ActiveApplicationSensor(runtime),
        ActiveWindowSensor(runtime),
        PortalStatusSensor(runtime),
        EnforcementStatusSensor(runtime),
        ScreenAssessmentSensor(runtime),
        ScreenSafetySensor(runtime),
        DailyActivitySummarySensor(runtime),
        WeeklyActivitySummarySensor(runtime),
    ]
    if runtime.device_type == DEVICE_TYPE_ANDROID:
        entities.append(InstalledAppsSensor(runtime))
    async_add_entities(entities)
    # The pending-setups sensor is integration-wide, so only the entry that
    # owns the pending entities creates it.
    if claim_pending_entities(hass, entry.entry_id):
        async_add_entities([PendingSetupsSensor(hass)])


class ActiveApplicationSensor(ParentalDeviceEntity, SensorEntity):
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
            "username": self.runtime.reported_username,
            "agent_version": self.runtime.agent_version,
        }


class ActiveWindowSensor(ParentalDeviceEntity, SensorEntity):
    _attr_name = "Active window"
    _attr_icon = "mdi:monitor-eye"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_active_window"

    @property
    def native_value(self) -> str:
        return self.runtime.active_window or "Idle"


class PortalStatusSensor(ParentalDeviceEntity, SensorEntity):
    """Sanitised child-kiosk status reported by the Windows activity app."""

    _attr_name = "Points portal status"
    _attr_icon = "mdi:web-check"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_portal_status"

    @property
    def native_value(self) -> str:
        return self.runtime.ui_status

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "last_error": self.runtime.ui_error or None,
            "reported_at": (
                self.runtime.ui_reported_at.isoformat()
                if self.runtime.ui_reported_at
                else None
            ),
            "agent_version": self.runtime.agent_version or None,
        }


class EnforcementStatusSensor(ParentalDeviceEntity, SensorEntity):
    """Android fail-open / Accessibility state, and the same field on Windows."""

    _attr_name = "Enforcement status"
    _attr_icon = "mdi:shield-alert"

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
            "fail_open": self.runtime.ui_status == "fail_open",
            "accessibility_disabled": self.runtime.ui_status
            == "accessibility_disabled",
        }


class ScreenAssessmentSensor(ParentalDeviceEntity, SensorEntity):
    """Latest text assessment; raw screenshots are never retained."""

    _attr_name = "Screen assessment"
    _attr_icon = "mdi:image-search"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_screen_assessment"

    @property
    def available(self) -> bool:
        return self.runtime.screen_monitor is not None

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


class ScreenSafetySensor(ParentalDeviceEntity, SensorEntity):
    """Latest structured concern level for automation triggers."""

    _attr_name = "Screen safety"
    _attr_icon = "mdi:shield-check"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_screen_safety"

    @property
    def native_value(self) -> str:
        return self.runtime.screen_monitor.last_risk_level

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


class _ActivitySummarySensor(ParentalDeviceEntity, SensorEntity):
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
    period = "today"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_daily_activity_summary"


class WeeklyActivitySummarySensor(_ActivitySummarySensor):
    _attr_name = "Weekly activity summary"
    _attr_icon = "mdi:calendar-week"
    period = "week"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_weekly_activity_summary"


class InstalledAppsSensor(ParentalDeviceEntity, SensorEntity):
    """The launchable apps the phone last reported, for choosing an allowlist."""

    _attr_name = "Installed apps"
    _attr_icon = "mdi:apps"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_installed_apps"

    @property
    def native_value(self) -> int:
        return len(self.runtime.installed_apps)

    @property
    def extra_state_attributes(self) -> dict:
        return {"apps": [dict(app) for app in self.runtime.installed_apps]}


class PendingSetupsSensor(SensorEntity):
    """How many phones are waiting for a parent to approve their setup.

    Integration-wide rather than per device: the name is spelled so that Home
    Assistant derives the documented entity id
    ``sensor.rowe_pc_blocker_pending_setups`` from it.
    """

    _attr_has_entity_name = False
    _attr_name = "Rowe PC blocker pending setups"
    _attr_icon = "mdi:cellphone-arrow-down"
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
