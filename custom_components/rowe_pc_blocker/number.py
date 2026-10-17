"""Wake alarm volume."""

from __future__ import annotations

from homeassistant.components.number import NumberEntity, NumberMode

from .const import DEVICE_TYPE_ANDROID, DOMAIN
from .entity import ParentalDeviceEntity


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    if runtime.device_type == DEVICE_TYPE_ANDROID:
        async_add_entities([WakeAlarmVolume(runtime)])


class WakeAlarmVolume(ParentalDeviceEntity, NumberEntity):
    """How loud the phone's alarm rings, as a percentage of its maximum.

    A slider rather than a box because a parent sets this by feel, not by
    typing an exact number. A parent setting, so it stays available while the
    phone is offline; the phone reads the change on its next state poll.
    """

    _attr_name = "Wake alarm volume"
    _attr_icon = "mdi:volume-high"
    _attr_native_min_value = 0
    _attr_native_max_value = 100
    _attr_native_step = 5
    _attr_native_unit_of_measurement = "%"
    _attr_mode = NumberMode.SLIDER

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_wake_alarm_volume"

    @property
    def native_value(self) -> float:
        return float(self.runtime.wake_alarm_volume)

    async def async_set_native_value(self, value: float) -> None:
        # The shared validator accepts an exact percentage from 0 to 100 and
        # ignores anything else, so an out-of-range service call leaves the
        # stored volume alone rather than clamping it silently.
        await self.runtime.async_update_wake_alarm(volume=value)
