"""Base entities for Parental Device Blocker."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity
from homeassistant.util import dt as dt_util

from .agent_status import agent_is_online
from .const import DEVICE_TYPE_ANDROID, DOMAIN, ONLINE_TIMEOUT_SECONDS


class ParentalDeviceEntity(Entity):
    """Base entity attached to one configured device."""

    _attr_has_entity_name = True

    def __init__(self, runtime) -> None:
        self.runtime = runtime

    @property
    def device_info(self) -> DeviceInfo:
        """The device this entity belongs to.

        The agent version is a fact about the device, not about any one
        reading, so it is reported here as the firmware version instead of
        being repeated as an attribute on several sensors.
        """
        return DeviceInfo(
            identifiers={(DOMAIN, self.runtime.device_id)},
            name=self.runtime.device_name,
            manufacturer="Parental Device Blocker",
            model=(
                "Android Accessibility Agent"
                if self.runtime.device_type == DEVICE_TYPE_ANDROID
                else "Windows PC Agent"
            ),
            sw_version=self.runtime.agent_version or None,
        )

    @property
    def agent_online(self) -> bool:
        """True while the agent has checked in within the online timeout."""
        return agent_is_online(
            self.runtime.last_seen, dt_util.utcnow(), ONLINE_TIMEOUT_SECONDS
        )

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(self.hass, self.runtime.signal, self.async_write_ha_state)
        )


class AgentTelemetryEntity(ParentalDeviceEntity):
    """An entity whose value only an agent check-in refreshes.

    While the agent is silent the stored value is stale, so the entity reports
    unavailable rather than presenting it as live. The block switch and the
    parent-editable text entities deliberately do not derive from this: a
    parent must still be able to set them while the device is offline. The
    agent-online binary sensor does not either — it is what reports the
    offline state, and it carries the staleness detail.
    """

    @property
    def available(self) -> bool:
        return self.agent_online
