"""Device buttons: screen check, location, and the challenge decision pair."""

from __future__ import annotations

from datetime import timedelta

from homeassistant.components.button import ButtonEntity
from homeassistant.core import callback
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.event import async_track_time_interval

from .const import DEVICE_TYPE_ANDROID, DOMAIN, SIGNAL_PENDING
from .entity import ParentalDeviceEntity
from .pending import (
    async_approve_request,
    async_approve_unblock_request,
    async_reject_request,
    async_reject_unblock_request,
    awaiting_requests,
    claim_pending_entities,
    unblock_requests,
)
from .runtime import CHALLENGE_SUBMITTED

APPROVE = "approve"
REJECT = "reject"


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    entities: list[ButtonEntity] = [CheckScreenButton(runtime)]
    # Only a phone has a position to ask for, and only a phone carries a chore
    # challenge a parent decides on.
    if runtime.device_type == DEVICE_TYPE_ANDROID:
        entities.extend(
            [
                RequestLocationButton(runtime),
                ApproveChallengeButton(runtime),
                RejectChallengeButton(runtime),
            ]
        )
    async_add_entities(entities)
    # The approve/reject buttons are integration-wide, so only the entry that
    # owns the pending entities creates them.
    if claim_pending_entities(hass, entry.entry_id):
        entry.async_on_unload(PendingRequestButtons(hass, async_add_entities).async_stop)


class RequestLocationButton(ParentalDeviceEntity, ButtonEntity):
    """Ask the phone for one position fix.

    The request rides the phone's next state poll and expires after ten
    minutes, so pressing again while one is pending simply replaces it with a
    fresh id. The answer shows up on the Location tracker.
    """

    _attr_name = "Request location"
    _attr_icon = "mdi:crosshairs-gps"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_request_location"

    async def async_press(self) -> None:
        await self.runtime.async_request_location()


class ChallengeDecisionButton(ParentalDeviceEntity, ButtonEntity):
    """Answer a challenge the child has submitted.

    Pressing these is the same as calling ``approve_challenge`` /
    ``reject_challenge``, but the pair sits on the device page next to the
    evidence photo, so a parent can decide from the dashboard instead of
    writing a service call. Both stay unavailable until a submission is
    waiting, which is the only time a decision means anything.
    """

    _attr_icon = "mdi:check-circle"

    @property
    def available(self) -> bool:
        return self.runtime.challenge_state == CHALLENGE_SUBMITTED


class ApproveChallengeButton(ChallengeDecisionButton):
    """Let the child back in after their photo and phrase."""

    _attr_name = "Approve challenge"
    _attr_icon = "mdi:check-circle"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_approve_challenge"

    async def async_press(self) -> None:
        await self.runtime.async_approve_challenge()


class RejectChallengeButton(ChallengeDecisionButton):
    """Send the challenge back so the chore can be done again."""

    _attr_name = "Reject challenge"
    _attr_icon = "mdi:close-circle"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_reject_challenge"

    async def async_press(self) -> None:
        await self.runtime.async_reject_challenge()


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


class PendingRequestButtons:
    """One approve/reject button pair per live pending request.

    Both kinds of request are answered from the dashboard — a phone asking to
    be set up, and a phone asking for one blocked app to be allowed — so one
    manager keeps a pair of buttons in step with each. Requests arrive and are
    answered while Home Assistant runs, so the buttons are added and removed as
    the pending list changes.
    """

    def __init__(self, hass, async_add_entities) -> None:
        self.hass = hass
        self._async_add_entities = async_add_entities
        self._buttons: dict[str, list[ButtonEntity]] = {}
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
        requests: dict[str, tuple[object, type]] = {}
        for request in awaiting_requests(self.hass):
            requests[request.request_id] = (request, PendingSetupButton)
        for request in unblock_requests(self.hass):
            requests[request.request_id] = (request, PendingUnblockButton)
        for request_id, buttons in list(self._buttons.items()):
            if request_id in requests:
                continue
            del self._buttons[request_id]
            for button in buttons:
                self.hass.async_create_task(self._async_remove(button))
        new_buttons: list[ButtonEntity] = []
        for request_id, (request, button_class) in requests.items():
            if request_id in self._buttons:
                continue
            buttons = [
                button_class(self.hass, request, action)
                for action in (APPROVE, REJECT)
            ]
            self._buttons[request_id] = buttons
            new_buttons.extend(buttons)
        if new_buttons:
            self._async_add_entities(new_buttons)

    async def _async_remove(self, button: ButtonEntity) -> None:
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


class PendingUnblockButton(ButtonEntity):
    """Approve or reject one phone's request to allow an app.

    The name is spelled so that Home Assistant derives the documented entity id
    ``button.rowe_pc_blocker_approve_unblock_<request_id>`` (or ``reject``)
    from it.
    """

    _attr_has_entity_name = False

    def __init__(self, hass, request, action: str) -> None:
        self.hass = hass
        self._request_id = request.request_id
        self._device_id = request.device_id
        self._package = request.package
        self._label = request.label
        self._action = action
        self._attr_name = f"Rowe PC blocker {action} unblock {request.request_id}"
        self._attr_unique_id = f"unblock_{action}_{request.request_id}"
        self._attr_icon = (
            "mdi:check-circle-outline"
            if action == APPROVE
            else "mdi:close-circle-outline"
        )

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "request_id": self._request_id,
            "device_id": self._device_id,
            "package": self._package,
            "label": self._label,
        }

    async def async_press(self) -> None:
        if self._action == APPROVE:
            await async_approve_unblock_request(self.hass, self._request_id)
        else:
            await async_reject_unblock_request(self.hass, self._request_id)
