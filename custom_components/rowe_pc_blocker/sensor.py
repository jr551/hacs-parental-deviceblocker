"""PC activity sensors."""

from homeassistant.components.sensor import SensorEntity

from .const import DEVICE_TYPE_ANDROID, DOMAIN
from .entity import ParentalDeviceEntity


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    entities = [
        ActiveApplicationSensor(runtime),
        ActiveWindowSensor(runtime),
        PortalStatusSensor(runtime),
        ScreenAssessmentSensor(runtime),
        ScreenSafetySensor(runtime),
        DailyActivitySummarySensor(runtime),
        WeeklyActivitySummarySensor(runtime),
    ]
    if runtime.device_type == DEVICE_TYPE_ANDROID:
        entities.append(MediaBackupSensor(runtime))
    async_add_entities(entities)


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


class MediaBackupSensor(ParentalDeviceEntity, SensorEntity):
    """Privacy-minimised Android photo/video backup progress."""

    _attr_name = "Media backup"
    _attr_icon = "mdi:cloud-upload"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_media_backup"

    @property
    def native_value(self) -> str:
        return self.runtime.backup_status.replace("_", " ").title()

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "uploaded": self.runtime.backup_uploaded,
            "skipped": self.runtime.backup_skipped,
            "initial_complete": self.runtime.backup_initial_complete,
            "initial_sync_wifi_only": True,
            "last_success": (
                self.runtime.backup_last_success.isoformat()
                if self.runtime.backup_last_success
                else None
            ),
            "last_error": self.runtime.backup_error or None,
            "credentials_exposed_to_device": False,
            "source": "device_reported",
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
