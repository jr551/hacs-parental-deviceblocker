"""Parent message text entity."""

from homeassistant.components.text import TextEntity
from homeassistant.helpers.restore_state import RestoreEntity

from .const import DOMAIN
from .entity import ParentalDeviceEntity


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    async_add_entities([PcMessageText(hass.data[DOMAIN][entry.entry_id])])


class PcMessageText(ParentalDeviceEntity, TextEntity, RestoreEntity):
    _attr_name = "Block message"
    _attr_icon = "mdi:message-alert"
    _attr_native_max = 255

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_block_message"

    @property
    def native_value(self) -> str:
        return self.runtime.message

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        old = await self.async_get_last_state()
        if old is not None and old.state not in ("unknown", "unavailable"):
            self.runtime.message = old.state

    async def async_set_value(self, value: str) -> None:
        self.runtime.message = value[:255]
        self.runtime.notify()
