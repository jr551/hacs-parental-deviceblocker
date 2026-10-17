"""Agent connectivity sensor."""

from datetime import timedelta

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.helpers.event import async_track_time_interval
from homeassistant.util import dt as dt_util

from .agent_status import agent_is_online
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
        return agent_is_online(
            self.runtime.last_seen, dt_util.utcnow(), ONLINE_TIMEOUT_SECONDS
        )

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "last_seen": (
                self.runtime.last_seen.isoformat() if self.runtime.last_seen else None
            ),
        }

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.async_on_remove(
            async_track_time_interval(
                self.hass,
                self._handle_interval,
                timedelta(seconds=30),
            )
        )

    async def _handle_interval(self, _now) -> None:
        # The tick is the only thing that runs while an agent is silent, so it
        # is also what notices the agent going stale and coming back.
        await self.runtime.check_agent_stale()
        self.async_write_ha_state()
