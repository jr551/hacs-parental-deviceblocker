"""Narrow device-authenticated API used by installed agents."""

from __future__ import annotations

import hmac
import re
from datetime import timedelta

from aiohttp import web

from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

from .api_security import (
    browser_origin_allowed,
    device_authorized,
    extract_device_key,
    integration_auth_blocked,
    parent_pin_ip_blocked,
    portal_response_headers,
    portal_session_cookie,
    read_json_object,
    record_parent_pin_failure,
    reject_unauthorized,
    request_is_secure,
    valid_device_id,
)
from .const import (
    CONF_PARENT_PIN,
    DOMAIN,
    EVENT_PARENT_OVERRIDE,
    PARENT_OVERRIDE_MINUTES,
    PARENT_PIN_LOCKOUT_MINUTES,
    PARENT_PIN_MAX_ATTEMPTS,
)
from .portal import PORTAL_HTML, build_portal_snapshot, resolve_child_identity
from .protocol_surface import (
    BACKUP_CONFIG_DISABLED_BODY,
    BACKUP_PRESIGN_DISABLED_BODY,
    BACKUP_PRESIGN_DISABLED_STATUS,
)


def _parent_pin(hass: HomeAssistant, runtime) -> str:
    """Return the configured parent PIN, or an empty value when disabled."""
    entry = hass.config_entries.async_get_entry(runtime.entry_id)
    if entry is not None:
        configured = str(entry.options.get(CONF_PARENT_PIN, "")).strip()
        if configured:
            return configured
    return ""


def _pin_attempts(hass: HomeAssistant) -> dict:
    return hass.data.setdefault(DOMAIN, {}).setdefault("_parent_pin_attempts", {})


def _pin_lockout_remaining(hass: HomeAssistant, device_id: str) -> int:
    """Seconds left on a lockout, or 0 when the device may try again."""
    record = _pin_attempts(hass).get(device_id)
    if not record or not record.get("locked_until"):
        return 0
    remaining = (record["locked_until"] - dt_util.utcnow()).total_seconds()
    if remaining <= 0:
        _pin_attempts(hass).pop(device_id, None)
        return 0
    return int(remaining)


def _runtime_for_request(hass: HomeAssistant, device_id: str):
    return next(
        (
            item
            for item in hass.data.get(DOMAIN, {}).values()
            if getattr(item, "device_id", None) == device_id
        ),
        None,
    )


async def _require_runtime(
    hass: HomeAssistant,
    request: web.Request,
    device_id: str,
    *,
    allow_query: bool = False,
    allow_cookie: bool = False,
):
    if not valid_device_id(device_id):
        await reject_unauthorized(hass, request)
    if integration_auth_blocked(hass, request):
        raise web.HTTPUnauthorized()
    runtime = _runtime_for_request(hass, device_id)
    supplied = extract_device_key(request, allow_query=allow_query)
    if not device_authorized(
        hass,
        runtime,
        supplied,
        request,
        allow_cookie=allow_cookie,
    ):
        await reject_unauthorized(hass, request)
    return runtime


def _state_payload(state) -> dict:
    return {"state": state.state, "attributes": dict(state.attributes)}


def _portal_context(hass: HomeAssistant, runtime):
    point_states = [
        state
        for state in hass.states.async_all("sensor")
        if state.entity_id.startswith("sensor.family_chore_manager_")
        and state.entity_id.endswith("_points")
        and state.attributes.get("child_id")
    ]
    child = resolve_child_identity(
        runtime.windows_username,
        runtime.device_name,
        [_state_payload(state) for state in point_states],
    )
    if child is None:
        return None

    points = next(
        state
        for state in point_states
        if str(state.attributes.get("child_id", "")) == child.child_id
    )
    stats = next(
        (
            state
            for state in hass.states.async_all("sensor")
            if state.entity_id.startswith("sensor.family_chore_manager_")
            and state.entity_id.endswith("_stats")
            and str(state.attributes.get("child_id", "")) == child.child_id
        ),
        None,
    )
    entity_ids = {
        "rewards": "sensor.family_chore_manager_rewards",
        "activity": "sensor.family_chore_manager_activity",
        "chores": "sensor.family_chore_manager_chores",
        "availability": "sensor.family_chore_manager_chore_availability",
    }
    entities = {"points": _state_payload(points)}
    entities["stats"] = _state_payload(stats) if stats is not None else {}
    for name, entity_id in entity_ids.items():
        state = hass.states.get(entity_id)
        entities[name] = _state_payload(state) if state is not None else {}

    buttons = [
        _state_payload(state)
        for state in hass.states.async_all("button")
        if state.entity_id.startswith("button.family_chore_manager_")
    ]
    return child, entities, buttons


def _require_browser_csrf(request: web.Request) -> None:
    if not browser_origin_allowed(request):
        raise web.HTTPForbidden()


class PcStateView(HomeAssistantView):
    """Return the desired state for one PC."""

    url = "/api/rowe_pc_blocker/{device_id}/state"
    name = "api:rowe_pc_blocker:state"
    requires_auth = False

    async def get(self, request: web.Request, device_id: str) -> web.Response:
        hass = request.app["hass"]
        runtime = await _require_runtime(hass, request, device_id)
        runtime.last_seen = dt_util.utcnow()
        runtime.notify()
        return self.json(
            {
                "device_id": runtime.device_id,
                "blocked": runtime.effective_blocked,
                "block_requested": runtime.blocked,
                "message": runtime.message,
                "windows_username": runtime.windows_username,
                "device_type": runtime.device_type,
                "enforce_at": runtime.enforce_at.isoformat() if runtime.enforce_at else None,
                "extension_available": runtime.extension_available,
                "extension_available_at": (
                    runtime.extension_available_at.isoformat()
                    if runtime.extension_available_at
                    else None
                ),
                "extension_until": (
                    runtime.extension_until.isoformat() if runtime.extension_until else None
                ),
            }
        )


class PcActivityView(HomeAssistantView):
    """Accept foreground-window changes and heartbeats."""

    url = "/api/rowe_pc_blocker/{device_id}/activity"
    name = "api:rowe_pc_blocker:activity"
    requires_auth = False

    async def post(self, request: web.Request, device_id: str) -> web.Response:
        hass = request.app["hass"]
        runtime = await _require_runtime(hass, request, device_id)
        try:
            payload = await read_json_object(request)
        except web.HTTPBadRequest as err:
            raise web.HTTPBadRequest(text="Expected a JSON object") from err
        runtime.update_activity(payload)
        return self.json({"ok": True})


class PcExtensionView(HomeAssistantView):
    """Grant the once-hourly five-minute save-work extension."""

    url = "/api/rowe_pc_blocker/{device_id}/extension"
    name = "api:rowe_pc_blocker:extension"
    requires_auth = False

    async def post(self, request: web.Request, device_id: str) -> web.Response:
        hass = request.app["hass"]
        runtime = await _require_runtime(hass, request, device_id)
        await read_json_object(request)
        granted = await runtime.async_request_extension()
        return self.json(
            {
                "granted": granted,
                "extension_until": (
                    runtime.extension_until.isoformat() if runtime.extension_until else None
                ),
                "extension_available_at": (
                    runtime.extension_available_at.isoformat()
                    if runtime.extension_available_at
                    else None
                ),
            },
            status_code=200 if granted else 429,
        )


class PcPortalView(HomeAssistantView):
    """Serve a device-authenticated, child-scoped points kiosk."""

    url = "/api/rowe_pc_blocker/{device_id}/portal"
    name = "api:rowe_pc_blocker:portal"
    requires_auth = False

    async def get(self, request: web.Request, device_id: str) -> web.Response:
        hass = request.app["hass"]
        runtime = await _require_runtime(
            hass, request, device_id, allow_query=True, allow_cookie=True
        )
        headers = portal_response_headers()
        headers["Set-Cookie"] = portal_session_cookie(
            request, runtime, secure=request_is_secure(request)
        )
        return web.Response(
            text=PORTAL_HTML,
            content_type="text/html",
            charset="utf-8",
            headers=headers,
        )


class PcPortalDataView(HomeAssistantView):
    """Return only the points, chores, shop, and history for this PC's child."""

    url = "/api/rowe_pc_blocker/{device_id}/portal/data"
    name = "api:rowe_pc_blocker:portal_data"
    requires_auth = False

    async def get(self, request: web.Request, device_id: str) -> web.Response:
        hass = request.app["hass"]
        runtime = await _require_runtime(hass, request, device_id, allow_cookie=True)
        headers = portal_response_headers()

        context = _portal_context(hass, runtime)
        if context is None:
            return self.json(
                {"error": "This PC is not mapped to a compatible chore-manager child."},
                status_code=404,
                headers=headers,
            )
        child, entities, buttons = context
        return self.json(
            build_portal_snapshot(child, entities, buttons),
            headers=headers,
        )


class PcPortalActionView(HomeAssistantView):
    """Press only a compatible chore-manager button belonging to this child."""

    url = "/api/rowe_pc_blocker/{device_id}/portal/action"
    name = "api:rowe_pc_blocker:portal_action"
    requires_auth = False

    async def post(self, request: web.Request, device_id: str) -> web.Response:
        hass = request.app["hass"]
        _require_browser_csrf(request)
        runtime = await _require_runtime(hass, request, device_id, allow_cookie=True)
        try:
            payload = await read_json_object(request)
        except web.HTTPBadRequest:
            return self.json({"error": "Expected a JSON object."}, status_code=400)
        action_type = str(payload.get("type", ""))[:64]
        item_id = str(payload.get("id", ""))[:64]
        item_key = {
            "claim_reward": "reward_id",
            "complete_chore": "chore_id",
        }.get(action_type)
        if item_key is None or re.fullmatch(r"[A-Za-z0-9_-]{4,64}", item_id) is None:
            return self.json({"error": "That portal action is not allowed."}, status_code=400)

        context = _portal_context(hass, runtime)
        if context is None:
            return self.json({"error": "No child is mapped to this PC."}, status_code=404)
        child, _entities, _buttons = context
        button = next(
            (
                state
                for state in hass.states.async_all("button")
                if state.entity_id.startswith("button.family_chore_manager_")
                and state.state != "unavailable"
                and str(state.attributes.get("child_id", "")) == child.child_id
                and str(state.attributes.get(item_key, "")) == item_id
            ),
            None,
        )
        if button is None:
            return self.json(
                {"error": "That item is not currently available for this child."},
                status_code=409,
            )
        reserved = action_type != "claim_reward" or await runtime.async_reserve_portal_reward(
            item_id
        )
        if not reserved:
            return self.json(
                {
                    "error": (
                        "That reward has already been submitted for this lock. "
                        "Wait for the PC to unlock or ask a parent for help."
                    )
                },
                status_code=409,
            )
        try:
            await hass.services.async_call(
                "button",
                "press",
                {"entity_id": button.entity_id},
                blocking=True,
            )
        except Exception:
            if action_type == "claim_reward":
                await runtime.async_release_portal_reward(item_id)
            raise
        return self.json({"ok": True})


class PcParentOverrideView(HomeAssistantView):
    """Let a parent grant one extra hour at the locked device with the parent PIN."""

    url = "/api/rowe_pc_blocker/{device_id}/parent_override"
    name = "api:rowe_pc_blocker:parent_override"
    requires_auth = False

    async def post(self, request: web.Request, device_id: str) -> web.Response:
        hass = request.app["hass"]
        _require_browser_csrf(request)
        runtime = await _require_runtime(hass, request, device_id, allow_cookie=True)

        parent_pin = _parent_pin(hass, runtime)
        if not parent_pin:
            return self.json(
                {"error": "Parent override is not configured."},
                status_code=503,
            )

        if parent_pin_ip_blocked(hass, request):
            return self.json(
                {"error": "Too many wrong PINs. Try again later."},
                status_code=429,
            )

        locked_for = _pin_lockout_remaining(hass, device_id)
        if locked_for:
            return self.json(
                {
                    "error": f"Too many wrong PINs. Try again in "
                    f"{max(1, round(locked_for / 60))} minute(s).",
                    "locked_seconds": locked_for,
                },
                status_code=429,
            )

        try:
            payload = await read_json_object(request)
        except web.HTTPBadRequest:
            return self.json({"error": "Expected a JSON object."}, status_code=400)
        supplied = str(payload.get("pin", "")).strip()[:64]

        if not hmac.compare_digest(supplied, parent_pin):
            record_parent_pin_failure(hass, request)
            attempts = _pin_attempts(hass)
            record = attempts.setdefault(device_id, {"failures": 0, "locked_until": None})
            record["failures"] += 1
            remaining = PARENT_PIN_MAX_ATTEMPTS - record["failures"]
            if remaining <= 0:
                record["locked_until"] = dt_util.utcnow() + timedelta(
                    minutes=PARENT_PIN_LOCKOUT_MINUTES
                )
                return self.json(
                    {
                        "error": "Wrong PIN. Locked for "
                        f"{PARENT_PIN_LOCKOUT_MINUTES} minutes.",
                        "attempts_remaining": 0,
                    },
                    status_code=429,
                )
            return self.json(
                {
                    "error": f"Wrong PIN. {remaining} attempt(s) left.",
                    "attempts_remaining": remaining,
                },
                status_code=403,
            )

        _pin_attempts(hass).pop(device_id, None)
        until = await runtime.async_grant_parent_override()
        if until is None:
            return self.json(
                {"error": "Device is not currently blocked."}, status_code=409
            )
        context = _portal_context(hass, runtime)
        child_id = context[0].child_id if context else ""
        child_name = context[0].name if context else runtime.device_name
        hass.bus.async_fire(
            EVENT_PARENT_OVERRIDE,
            {
                "device_id": device_id,
                "device_name": runtime.device_name,
                "child_id": child_id,
                "child_name": child_name,
                "minutes": PARENT_OVERRIDE_MINUTES,
                "until": until.isoformat(),
            },
        )
        return self.json(
            {
                "ok": True,
                "minutes": PARENT_OVERRIDE_MINUTES,
                "until": until.isoformat(),
            }
        )


class AndroidBackupConfigView(HomeAssistantView):
    """Legacy Android backup probe; server-side backup was removed in 0.10.0."""

    url = "/api/rowe_pc_blocker/{device_id}/backup/config"
    name = "api:rowe_pc_blocker:backup_config"
    requires_auth = False

    async def get(self, request: web.Request, device_id: str) -> web.Response:
        hass = request.app["hass"]
        await _require_runtime(hass, request, device_id)
        return self.json(BACKUP_CONFIG_DISABLED_BODY)


class AndroidBackupPresignView(HomeAssistantView):
    """Legacy presign endpoint; always disabled after 0.10.0."""

    url = "/api/rowe_pc_blocker/{device_id}/backup/presign"
    name = "api:rowe_pc_blocker:backup_presign"
    requires_auth = False

    async def post(self, request: web.Request, device_id: str) -> web.Response:
        hass = request.app["hass"]
        await _require_runtime(hass, request, device_id)
        await read_json_object(request)
        return self.json(
            BACKUP_PRESIGN_DISABLED_BODY,
            status_code=BACKUP_PRESIGN_DISABLED_STATUS,
        )


class AndroidBackupStatusView(HomeAssistantView):
    """Accept bounded backup progress for the parent-facing status sensor."""

    url = "/api/rowe_pc_blocker/{device_id}/backup/status"
    name = "api:rowe_pc_blocker:backup_status"
    requires_auth = False

    async def post(self, request: web.Request, device_id: str) -> web.Response:
        hass = request.app["hass"]
        runtime = await _require_runtime(hass, request, device_id)
        payload = await read_json_object(request)
        runtime.update_backup_status(payload)
        return self.json({"ok": True})


def async_register_api(hass: HomeAssistant) -> None:
    """Register API views once."""
    if hass.data.setdefault(DOMAIN, {}).get("_api_registered"):
        return
    hass.http.register_view(PcStateView)
    hass.http.register_view(PcParentOverrideView)
    hass.http.register_view(PcActivityView)
    hass.http.register_view(PcExtensionView)
    hass.http.register_view(PcPortalView)
    hass.http.register_view(PcPortalDataView)
    hass.http.register_view(PcPortalActionView)
    hass.http.register_view(AndroidBackupConfigView)
    hass.http.register_view(AndroidBackupPresignView)
    hass.http.register_view(AndroidBackupStatusView)
    hass.data[DOMAIN]["_api_registered"] = True
