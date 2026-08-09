"""Base entity for Parental Device Blocker."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity

from .const import DOMAIN


class ParentalDeviceEntity(Entity):
    """Base entity attached to one configured PC."""

    _attr_has_entity_name = True

    def __init__(self, runtime) -> None:
        self.runtime = runtime
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, runtime.device_id)},
            name=runtime.device_name,
            manufacturer="Parental Device Blocker",
            model=(
                "Android Accessibility Agent"
                if runtime.device_type == "android"
                else "Windows PC Agent"
            ),
        )

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(self.hass, self.runtime.signal, self.async_write_ha_state)
        )
