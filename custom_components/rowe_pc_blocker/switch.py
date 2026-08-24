"""Blocking switch."""

import logging

from homeassistant.components import persistent_notification
from homeassistant.components.switch import SwitchEntity
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.restore_state import RestoreEntity

from .const import DOMAIN
from .entity import ParentalDeviceEntity

_LOGGER = logging.getLogger(__name__)


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    async_add_entities([PcBlockedSwitch(hass.data[DOMAIN][entry.entry_id])])


class PcBlockedSwitch(ParentalDeviceEntity, SwitchEntity, RestoreEntity):
    _attr_name = "Blocked"
    _attr_icon = "mdi:account-lock"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_blocked"

    @property
    def is_on(self) -> bool:
        return self.runtime.blocked

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        old = await self.async_get_last_state()
        if old is not None:
            self.runtime.blocked = old.state == "on"
            if self.runtime.blocked and self.runtime.block_requested_at is None:
                # Do not grant a fresh 30s grace after restart — the original
                # block time is lost, so enforce immediately (keep any active
                # override/extension, but do not create a new grace window).
                from homeassistant.util import dt as dt_util
                from datetime import timedelta
                from .const import INITIAL_GRACE_SECONDS
                # Set requested to grace-ago so enforce_at == now.
                self.runtime.block_requested_at = dt_util.utcnow() - timedelta(
                    seconds=INITIAL_GRACE_SECONDS
                )
                await self.runtime.async_save()
                self.runtime.notify()
    async def async_turn_on(self, **kwargs) -> None:
        await self.runtime.async_set_blocked(True)

    async def async_turn_off(self, **kwargs) -> None:
        # Unblocking is a parent action: allow admins and automations/scripts
        # (no user context), refuse everyone else and tell the parents.
        context = getattr(self, "context", None) or getattr(self, "_context", None)
        if context is not None and context.user_id:
            user = await self.hass.auth.async_get_user(context.user_id)
            if user is not None and not user.is_admin:
                who = user.name or context.user_id
                _LOGGER.warning(
                    "Refused unblock of %s by non-admin user %s",
                    self.runtime.device_id,
                    who,
                )
                persistent_notification.async_create(
                    self.hass,
                    f"{who} tried to turn off the block for "
                    f"{self.runtime.device_id} and was refused.",
                    title="PC block tamper attempt",
                    notification_id=f"rowe_pc_blocker_tamper_{self.runtime.device_id}",
                )
                self.async_write_ha_state()
                raise HomeAssistantError(
                    "Only a parent can turn the block off."
                )
        await self.runtime.async_set_blocked(False)

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "effective_blocked": self.runtime.effective_blocked,
            "enforce_at": self.runtime.enforce_at.isoformat() if self.runtime.enforce_at else None,
            "extension_available": self.runtime.extension_available,
            "extension_available_at": (
                self.runtime.extension_available_at.isoformat()
                if self.runtime.extension_available_at
                else None
            ),
        }
