"""Agent connectivity sensor."""

from datetime import timedelta

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.core import callback
from homeassistant.helpers.event import async_track_time_interval
from homeassistant.util import dt as dt_util

from .const import DOMAIN, ONLINE_TIMEOUT_SECONDS
from .entity import ParentalDeviceEntity


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    async_add_entities([PcOnlineSensor(hass.data[DOMAIN][entry.entry_id])])


class PcOnlineSensor(ParentalDeviceEntity, BinarySensorEntity):
    _attr_name = "Agent online"
    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_agent_online"

    @property
    def is_on(self) -> bool:
        return self.runtime.last_seen is not None and (
            dt_util.utcnow() - self.runtime.last_seen
        ).total_seconds() < ONLINE_TIMEOUT_SECONDS

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.async_on_remove(
            async_track_time_interval(
                self.hass,
                self._handle_interval,
                timedelta(seconds=30),
            )
        )

    @callback
    def _handle_interval(self, _now) -> None:
        self.async_write_ha_state()
