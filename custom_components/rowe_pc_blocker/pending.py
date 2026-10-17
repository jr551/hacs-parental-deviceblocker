"""Home Assistant glue for pending phone requests.

The request models and their rules live in ``provision.py``. This module owns
the ``.storage/rowe_pc_blocker.pending`` file, the approve/reject entry points
the dashboard buttons call, and the dispatcher signal that keeps the pending
entities in step.

Two kinds of request share the file: a phone asking to be set up, and a phone
asking for one blocked app to be allowed.
"""

from __future__ import annotations

import secrets

from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from .apps import (
    DEFAULT_ALLOWED_APPS,
    MAX_APP_FIELD_LENGTH,
    PACKAGE_PATTERN,
    append_allowed_app,
    parse_allowed_apps,
)
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
    PendingUnblockStore,
    PhoneEntry,
    STATUS_PENDING,
    UnblockRequest,
    decide_approval,
    unblock_pending_body,
)
from .runtime import runtime_for_device

STORE_KEY = f"{DOMAIN}.pending"
STORE_VERSION = 1


def pending_store(hass: HomeAssistant) -> PendingProvisionStore:
    """The in-memory store. Empty until ``async_load_pending`` has run."""
    return hass.data.setdefault(DOMAIN, {}).setdefault(
        "_pending", PendingProvisionStore()
    )


def unblock_store(hass: HomeAssistant) -> PendingUnblockStore:
    """The in-memory unblock store. Empty until ``async_load_pending`` has run."""
    return hass.data.setdefault(DOMAIN, {}).setdefault(
        "_unblock", PendingUnblockStore()
    )


async def async_load_pending(hass: HomeAssistant) -> PendingProvisionStore:
    """Load the pending requests once per Home Assistant run.

    The stores are created on first use, so this loads into whichever objects
    already exist rather than replacing them: an entity platform that reads a
    store before this runs must not be left holding an empty one.
    """
    data = hass.data.setdefault(DOMAIN, {})
    store = pending_store(hass)
    if data.get("_pending_loaded"):
        return store
    data["_pending_loaded"] = True
    unblock = unblock_store(hass)
    storage = Store(hass, STORE_VERSION, STORE_KEY)
    data["_pending_storage"] = storage
    loaded = await storage.async_load()
    store.load(loaded)
    unblock.load(loaded)
    return store


async def _async_save_pending(hass: HomeAssistant) -> None:
    data = hass.data.setdefault(DOMAIN, {})
    store = data.get("_pending")
    unblock = data.get("_unblock")
    storage = data.get("_pending_storage")
    if store is None or unblock is None or storage is None:
        return
    await storage.async_save({**store.dump(), **unblock.dump()})
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


def unblock_requests(
    hass: HomeAssistant, device_id: str | None = None
) -> list[UnblockRequest]:
    """Every live unblock request, or one device's, newest last."""
    store = unblock_store(hass)
    now = dt_util.utcnow()
    if device_id is None:
        return store.list(now)
    return store.for_device(device_id, now)


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


async def async_create_unblock_request(
    hass: HomeAssistant, runtime, package: str, label: str
) -> tuple[int, dict]:
    """Record one app a blocked phone wants allowed.

    Returns the HTTP status and body the phone is answered with. A package that
    is already allowed, or one a parent is already looking at, is a conflict
    rather than a second request.
    """
    package = package.strip()
    if PACKAGE_PATTERN.fullmatch(package) is None:
        return 400, {"error": "That is not an Android package name."}
    allowed = parse_allowed_apps(runtime.allowed_apps) or list(DEFAULT_ALLOWED_APPS)
    if package in allowed:
        return 409, {"error": "That app is already allowed on this phone."}

    await async_load_pending(hass)
    now = dt_util.utcnow()
    unblock = unblock_store(hass)
    if unblock.pending_for(runtime.device_id, package, now) is not None:
        return 409, {"error": "A parent already has a request for that app."}
    request = unblock.create(
        runtime.device_id,
        package,
        label.strip()[:MAX_APP_FIELD_LENGTH],
        now,
    )
    await _async_save_pending(hass)
    return 200, unblock_pending_body(request)


async def async_approve_unblock_request(hass: HomeAssistant, request_id: str) -> bool:
    """Allow one app on the device that asked, and drop the request.

    The package is appended to the device's allowlist, which is what the phone
    reads on its next state poll; the block screen then clears itself. A list
    that cannot take another app is left alone and the request stays pending,
    so a parent can shorten the list and press approve again.
    """
    await async_load_pending(hass)
    now = dt_util.utcnow()
    request = unblock_store(hass).get(request_id, now)
    if request is None:
        return False
    runtime = runtime_for_device(hass, request.device_id)
    if runtime is None:
        return False
    try:
        runtime.allowed_apps = append_allowed_app(runtime.allowed_apps, request.package)
    except ValueError:
        return False
    unblock_store(hass).remove(request_id, now)
    await _async_save_pending(hass)
    runtime.notify()
    return True


async def async_reject_unblock_request(hass: HomeAssistant, request_id: str) -> bool:
    """Decline one unblock request. The allowlist is left untouched."""
    await async_load_pending(hass)
    if not unblock_store(hass).remove(request_id, dt_util.utcnow()):
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
