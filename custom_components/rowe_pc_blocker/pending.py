"""Home Assistant glue for pending phone setup requests.

The request model and its rules live in ``provision.py``. This module owns the
``.storage/rowe_pc_blocker.pending`` file, the approve/reject entry points the
dashboard buttons call, and the dispatcher signal that keeps the pending
entities in step.
"""

from __future__ import annotations

import secrets

from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from .const import (
    CONF_API_KEY,
    CONF_DEVICE_ID,
    CONF_DEVICE_NAME,
    CONF_DEVICE_TYPE,
    CONF_WINDOWS_USERNAME,
    DEVICE_TYPE_ANDROID,
    DOMAIN,
    SIGNAL_PENDING,
)
from .provision import (
    ApprovalDecision,
    PendingProvisionStore,
    PendingRequest,
    PhoneEntry,
    STATUS_PENDING,
    decide_approval,
)

STORE_KEY = f"{DOMAIN}.pending"
STORE_VERSION = 1


def pending_store(hass: HomeAssistant) -> PendingProvisionStore:
    """The in-memory store. Empty until ``async_load_pending`` has run."""
    return hass.data.setdefault(DOMAIN, {}).setdefault(
        "_pending", PendingProvisionStore()
    )


async def async_load_pending(hass: HomeAssistant) -> PendingProvisionStore:
    """Load the pending requests once per Home Assistant run."""
    data = hass.data.setdefault(DOMAIN, {})
    store = data.get("_pending")
    if store is not None:
        return store
    store = PendingProvisionStore()
    storage = Store(hass, STORE_VERSION, STORE_KEY)
    data["_pending"] = store
    data["_pending_storage"] = storage
    store.load(await storage.async_load())
    return store


async def _async_save_pending(hass: HomeAssistant) -> None:
    data = hass.data.setdefault(DOMAIN, {})
    store = data.get("_pending")
    storage = data.get("_pending_storage")
    if store is None or storage is None:
        return
    await storage.async_save(store.dump())
    async_dispatcher_send(hass, SIGNAL_PENDING)


def pending_requests(hass: HomeAssistant) -> list[PendingRequest]:
    """Every live request, newest last, with expired ones pruned."""
    return pending_store(hass).list(dt_util.utcnow())


def awaiting_requests(hass: HomeAssistant) -> list[PendingRequest]:
    """The live requests still waiting for a parent to approve or reject.

    An approved request stays collectable for the rest of its 30 minutes so a
    phone that missed the answer can retry, but it is no longer waiting on a
    parent, so it is not listed here.
    """
    return [
        request
        for request in pending_requests(hass)
        if request.status == STATUS_PENDING
    ]


def claim_pending_entities(hass: HomeAssistant, entry_id: str) -> bool:
    """True when this entry should create the integration-wide pending entities.

    The pending-setups sensor and its approve/reject buttons belong to the
    integration rather than to one device, but entity platforms are set up once
    per config entry. The first entry to load owns them; every later entry
    would only create duplicates.
    """
    data = hass.data.setdefault(DOMAIN, {})
    owner = data.get("_pending_entities_owner")
    if owner is None:
        data["_pending_entities_owner"] = entry_id
        return True
    return owner == entry_id


def release_pending_entities(hass: HomeAssistant, entry_id: str) -> bool:
    """Drop the claim when the owning entry unloads. True when it was the owner."""
    data = hass.data.setdefault(DOMAIN, {})
    if data.get("_pending_entities_owner") != entry_id:
        return False
    data.pop("_pending_entities_owner", None)
    return True


def provision_entries(hass: HomeAssistant) -> list[PhoneEntry]:
    """The fields provision needs from every configured device."""
    entries: list[PhoneEntry] = []
    for entry in hass.config_entries.async_entries(DOMAIN):
        data = entry.data
        entries.append(
            PhoneEntry(
                device_id=str(data.get(CONF_DEVICE_ID, "")),
                device_name=str(data.get(CONF_DEVICE_NAME, "")),
                device_type=str(data.get(CONF_DEVICE_TYPE, "")),
                windows_username=str(data.get(CONF_WINDOWS_USERNAME, "")),
                api_key=str(data.get(CONF_API_KEY, "")),
            )
        )
    return entries


async def async_create_request(hass: HomeAssistant, child_name: str) -> PendingRequest:
    """Return the request this child should collect, creating a pending one if needed.

    An approved request keeps handing out the same credentials until it
    expires, so a phone that retries after a dropped response is not asked
    again. A second request for the same child reuses the pending one.
    """
    store = await async_load_pending(hass)
    now = dt_util.utcnow()
    approved = store.approved_for_child(child_name, now)
    if approved is not None:
        return approved
    pending = store.pending_for_child(child_name, now)
    if pending is not None:
        return pending
    request = store.create(child_name, now)
    await _async_save_pending(hass)
    return request


async def async_approve_request(
    hass: HomeAssistant, request_id: str
) -> ApprovalDecision:
    """Approve one pending request and resolve the credentials the phone collects."""
    store = await async_load_pending(hass)
    now = dt_util.utcnow()
    request = store.get(request_id, now)
    if request is None or request.status != STATUS_PENDING:
        return ApprovalDecision("unavailable", message="That setup request has expired.")

    decision = decide_approval(request.child_name, provision_entries(hass))
    if decision.action == "create":
        api_key = secrets.token_urlsafe(32)
        created = await _async_create_entry(
            hass, decision.device_id, request.child_name, api_key
        )
        if created:
            decision = ApprovalDecision(
                "create",
                device_id=decision.device_id,
                child_name=request.child_name,
                api_key=api_key,
            )
        else:
            # The entry appeared while the request waited, or the flow refused.
            again = decide_approval(request.child_name, provision_entries(hass))
            if again.action != "reuse":
                return ApprovalDecision(
                    "unavailable",
                    child_name=request.child_name,
                    message="Home Assistant could not set up this phone.",
                )
            decision = again
    if decision.action not in ("create", "reuse"):
        return decision

    # A created entry and a reused entry both hand the phone the same
    # credentials, so both have to be recorded before the phone can collect.
    store.approve(request_id, now, decision.device_id, decision.api_key)
    await _async_save_pending(hass)
    return decision


async def async_reject_request(hass: HomeAssistant, request_id: str) -> bool:
    """Decline one pending request. The phone is told a parent said no."""
    store = await async_load_pending(hass)
    if store.reject(request_id, dt_util.utcnow()) is None:
        return False
    await _async_save_pending(hass)
    return True


async def _async_create_entry(
    hass: HomeAssistant, device_id: str, child_name: str, api_key: str
) -> bool:
    """Create the phone's config entry through the normal config flow."""
    result = await hass.config_entries.flow.async_init(
        DOMAIN,
        context={"source": "user"},
        data={
            CONF_DEVICE_ID: device_id,
            CONF_DEVICE_NAME: f"{child_name}'s phone",
            CONF_DEVICE_TYPE: DEVICE_TYPE_ANDROID,
            CONF_WINDOWS_USERNAME: child_name,
            CONF_API_KEY: api_key,
        },
    )
    return result.get("type") == FlowResultType.CREATE_ENTRY
