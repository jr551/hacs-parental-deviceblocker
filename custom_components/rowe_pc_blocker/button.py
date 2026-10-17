"""On-demand screen-check button and the pending-setup approve/reject pair."""

from __future__ import annotations

from datetime import timedelta

from homeassistant.components.button import ButtonEntity
from homeassistant.core import callback
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.event import async_track_time_interval

from .const import DOMAIN, SIGNAL_PENDING
from .entity import ParentalDeviceEntity
from .pending import (
    async_approve_request,
    async_reject_request,
    awaiting_requests,
    claim_pending_entities,
)

APPROVE = "approve"
REJECT = "reject"


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    async_add_entities([CheckScreenButton(runtime)])
    # The approve/reject buttons are integration-wide, so only the entry that
    # owns the pending entities creates them.
    if claim_pending_entities(hass, entry.entry_id):
        entry.async_on_unload(PendingSetupButtons(hass, async_add_entities).async_stop)


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


class PendingSetupButtons:
    """One approve/reject button pair per live setup request.

    Requests arrive and are answered while Home Assistant runs, so the buttons
    are added and removed as the pending list changes.
    """

    def __init__(self, hass, async_add_entities) -> None:
        self.hass = hass
        self._async_add_entities = async_add_entities
        self._buttons: dict[str, list[PendingSetupButton]] = {}
        self._unsubscribe = async_dispatcher_connect(
            hass, SIGNAL_PENDING, self._sync
        )
        # A request that nobody answers expires on its own, without a signal,
        # so sweep for it rather than leaving a dead button on the dashboard.
        self._cancel_sweep = async_track_time_interval(
            hass, self._handle_sweep, timedelta(seconds=60)
        )
        self._sync()

    def async_stop(self) -> None:
        """Stop tracking requests when the owning entry unloads."""
        self._unsubscribe()
        self._cancel_sweep()

    @callback
    def _handle_sweep(self, _now) -> None:
        self._sync()

    @callback
    def _sync(self) -> None:
        requests = {
            request.request_id: request for request in awaiting_requests(self.hass)
        }
        for request_id, buttons in list(self._buttons.items()):
            if request_id in requests:
                continue
            del self._buttons[request_id]
            for button in buttons:
                self.hass.async_create_task(self._async_remove(button))
        new_buttons: list[PendingSetupButton] = []
        for request_id, request in requests.items():
            if request_id in self._buttons:
                continue
            buttons = [
                PendingSetupButton(self.hass, request, action)
                for action in (APPROVE, REJECT)
            ]
            self._buttons[request_id] = buttons
            new_buttons.extend(buttons)
        if new_buttons:
            self._async_add_entities(new_buttons)

    async def _async_remove(self, button: PendingSetupButton) -> None:
        """Drop an answered request's buttons, registry entry included."""
        await button.async_remove(force_remove=True)
        if button.entity_id:
            er.async_get(self.hass).async_remove(button.entity_id)


class PendingSetupButton(ButtonEntity):
    """Approve or reject one phone's setup request.

    The name is spelled so that Home Assistant derives the documented entity id
    ``button.rowe_pc_blocker_approve_<request_id>`` (or ``reject``) from it.
    """

    _attr_has_entity_name = False

    def __init__(self, hass, request, action: str) -> None:
        self.hass = hass
        self._request_id = request.request_id
        self._child_name = request.child_name
        self._action = action
        self._attr_name = f"Rowe PC blocker {action} {request.request_id}"
        self._attr_unique_id = f"pending_{action}_{request.request_id}"
        self._attr_icon = (
            "mdi:check-circle-outline"
            if action == APPROVE
            else "mdi:close-circle-outline"
        )

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "request_id": self._request_id,
            "child_name": self._child_name,
        }

    async def async_press(self) -> None:
        if self._action == APPROVE:
            await async_approve_request(self.hass, self._request_id)
        else:
            await async_reject_request(self.hass, self._request_id)
