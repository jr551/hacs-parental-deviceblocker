"""On-demand screen-check button."""

from homeassistant.components.button import ButtonEntity

from .const import DOMAIN
from .entity import ParentalDeviceEntity


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    async_add_entities([CheckScreenButton(runtime)])


class CheckScreenButton(ParentalDeviceEntity, ButtonEntity):
    """Request one read-only VNC screenshot assessment."""

    _attr_name = "Check screen now"
    _attr_icon = "mdi:monitor-eye"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_check_screen"

    @property
    def available(self) -> bool:
        monitor = self.runtime.screen_monitor
        return monitor is not None and monitor.settings.configured

    async def async_press(self) -> None:
        await self.runtime.screen_monitor.async_check_now("manual")
