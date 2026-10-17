"""Wake alarm time."""

from __future__ import annotations

from datetime import time as dt_time

from homeassistant.components.time import TimeEntity

from .const import DEVICE_TYPE_ANDROID, DOMAIN
from .entity import ParentalDeviceEntity


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    if runtime.device_type == DEVICE_TYPE_ANDROID:
        async_add_entities([WakeAlarmTime(runtime)])


class WakeAlarmTime(ParentalDeviceEntity, TimeEntity):
    """When the phone's wake alarm rings.

    A parent setting rather than an agent reading, so it stays available while
    the phone is offline. The change is runtime state, so it reaches the phone
    on its next state poll without reloading the integration.
    """

    _attr_name = "Wake alarm time"
    _attr_icon = "mdi:alarm"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_wake_alarm_time"

    @property
    def native_value(self) -> dt_time:
        return dt_time.fromisoformat(self.runtime.wake_alarm_time)

    async def async_set_value(self, value: dt_time) -> None:
        # The shared validator normalises a time that carries seconds and
        # rejects an unreadable one, so a malformed service call cannot move
        # the alarm. Nothing is stored when it does.
        await self.runtime.async_update_wake_alarm(at=str(value))
