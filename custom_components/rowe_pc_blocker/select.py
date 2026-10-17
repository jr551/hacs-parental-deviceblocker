"""Wake alarm day preset."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity

from .const import DEVICE_TYPE_ANDROID, DOMAIN
from .entity import ParentalDeviceEntity
from .wake_alarm import WAKE_DAYS

EVERY_DAY = "Every day"
WEEKDAYS = "Weekdays"
WEEKENDS = "Weekends"
CUSTOM = "Custom"

DAY_OPTIONS = (EVERY_DAY, WEEKDAYS, WEEKENDS, CUSTOM)

_WEEKDAYS = ("mon", "tue", "wed", "thu", "fri")
_WEEKENDS = ("sat", "sun")


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    if runtime.device_type == DEVICE_TYPE_ANDROID:
        async_add_entities([WakeAlarmDays(runtime)])


class WakeAlarmDays(ParentalDeviceEntity, SelectEntity):
    """The days the alarm rings, as the four choices a parent actually uses.

    Home Assistant's select platform needs one of a fixed list of strings, so
    a schedule that matches no preset is shown as ``Custom``; the real day
    list rides in the ``days`` attribute. Choosing ``Custom`` keeps whatever
    was stored because there is no more specific schedule to apply here.
    """

    _attr_name = "Wake alarm days"
    _attr_icon = "mdi:calendar-week"
    _attr_options = list(DAY_OPTIONS)

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_wake_alarm_days"

    @property
    def current_option(self) -> str:
        days = set(self.runtime.wake_alarm_days)
        if days == set(WAKE_DAYS):
            return EVERY_DAY
        if days == set(_WEEKDAYS):
            return WEEKDAYS
        if days == set(_WEEKENDS):
            return WEEKENDS
        return CUSTOM

    async def async_select_option(self, option: str) -> None:
        if option == EVERY_DAY:
            days = list(WAKE_DAYS)
        elif option == WEEKDAYS:
            days = list(_WEEKDAYS)
        elif option == WEEKENDS:
            days = list(_WEEKENDS)
        elif option == CUSTOM:
            days = list(self.runtime.wake_alarm_days)
        else:
            return
        await self.runtime.async_update_wake_alarm(days=days)

    @property
    def extra_state_attributes(self) -> dict:
        return {"days": list(self.runtime.wake_alarm_days)}
