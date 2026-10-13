"""HTTP hardening helpers for device-facing API views."""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import re
import secrets
import time
from typing import TYPE_CHECKING, Any

from aiohttp import web

from homeassistant.util import dt as dt_util

from .const import DOMAIN

if TYPE_CHECKING:
    from .runtime import PcRuntime

_LOGGER = logging.getLogger(__name__)

DEVICE_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
MAX_JSON_BYTES = 65_536
MIN_API_KEY_BYTES = 16  # 128-bit minimum entropy when url-safe decoded
PORTAL_COOKIE_NAME = "rowe_pc_blocker_portal"
PORTAL_SESSION_SECONDS = 3600
INTEGRATION_AUTH_WINDOW_SECONDS = 60
INTEGRATION_AUTH_MAX_FAILURES = 30
PARENT_PIN_IP_WINDOW_SECONDS = 900
PARENT_PIN_IP_MAX_FAILURES = 20

_PORTAL_SAFE_HEADERS = {
    "Cache-Control": "no-store",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
}


def valid_device_id(device_id: str) -> bool:
    return bool(device_id and DEVICE_ID_PATTERN.fullmatch(device_id))


def api_key_meets_minimum_entropy(api_key: str) -> bool:
    """Require at least 128 bits of key material (url-safe token)."""
    value = str(api_key or "").strip()
    if len(value) < 22:
        return False
    try:
        import base64

        padded = value + "=" * (-len(value) % 4)
        return len(base64.urlsafe_b64decode(padded)) >= MIN_API_KEY_BYTES
    except (ValueError, TypeError):
        return len(value.encode("utf-8")) >= MIN_API_KEY_BYTES


def generate_api_key() -> str:
    return secrets.token_urlsafe(32)


def _security_store(hass) -> dict[str, Any]:
    return hass.data.setdefault(DOMAIN, {}).setdefault("_api_security", {})


def _fake_compare_key(hass) -> str:
    store = _security_store(hass)
    key = store.get("_fake_key")
    if not key:
        key = secrets.token_urlsafe(32)
        store["_fake_key"] = key
    return key


def extract_device_key(request: web.Request, *, allow_query: bool = False) -> str:
    supplied = request.headers.get("X-Device-Blocker-Key", "")
    if not supplied:
        supplied = request.headers.get("X-Rowe-Key", "")
    if allow_query and not supplied:
        supplied = request.query.get("key", "")
    return str(supplied or "")


def _sign_portal_session(api_key: str, device_id: str, expires: int) -> str:
    digest = hmac.new(
        api_key.encode("utf-8"),
        f"{device_id}:{expires}".encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()[:32]
    return f"v1.{expires}.{digest}"


def portal_session_cookie(
    request: web.Request, runtime: PcRuntime, *, secure: bool
) -> str:
    expires = int(time.time()) + PORTAL_SESSION_SECONDS
    value = _sign_portal_session(runtime.api_key, runtime.device_id, expires)
    secure_flag = "; Secure" if secure else ""
    path = f"/api/rowe_pc_blocker/{runtime.device_id}/"
    return (
        f"{PORTAL_COOKIE_NAME}={value}; Path={path}; HttpOnly; SameSite=Strict; "
        f"Max-Age={PORTAL_SESSION_SECONDS}{secure_flag}"
    )


def portal_cookie_valid(runtime: PcRuntime, request: web.Request) -> bool:
    raw = request.cookies.get(PORTAL_COOKIE_NAME, "")
    if not raw:
        return False
    parts = raw.split(".", 2)
    if len(parts) != 3 or parts[0] != "v1":
        return False
    try:
        expires = int(parts[1])
    except ValueError:
        return False
    if expires < int(time.time()):
        return False
    if request.match_info.get("device_id") != runtime.device_id:
        return False
    expected = _sign_portal_session(runtime.api_key, runtime.device_id, expires)
    return hmac.compare_digest(raw, expected)


def device_authorized(
    hass,
    runtime: PcRuntime | None,
    supplied: str,
    request: web.Request,
    *,
    allow_cookie: bool = False,
) -> bool:
    if runtime is None:
        hmac.compare_digest(supplied or "", _fake_compare_key(hass))
        return False
    if supplied and hmac.compare_digest(supplied, runtime.api_key):
        return True
    if allow_cookie and portal_cookie_valid(runtime, request):
        return True
    if supplied:
        hmac.compare_digest(supplied, runtime.api_key)
    return False


def _client_ip(request: web.Request) -> str:
    remote = request.remote or "0.0.0.0"
    return str(remote)


def integration_auth_blocked(hass, request: web.Request) -> bool:
    store = _security_store(hass).setdefault("auth_failures", {})
    ip = _client_ip(request)
    now = dt_util.utcnow()
    record = store.get(ip)
    if record is None or (now - record["window"]).total_seconds() >= INTEGRATION_AUTH_WINDOW_SECONDS:
        return False
    return record["count"] >= INTEGRATION_AUTH_MAX_FAILURES


def _record_integration_auth_failure(hass, request: web.Request) -> None:
    store = _security_store(hass).setdefault("auth_failures", {})
    ip = _client_ip(request)
    now = dt_util.utcnow()
    record = store.get(ip)
    if record is None or (now - record["window"]).total_seconds() >= INTEGRATION_AUTH_WINDOW_SECONDS:
        store[ip] = {"window": now, "count": 1}
        return
    record["count"] += 1


def record_parent_pin_failure(hass, request: web.Request) -> None:
    store = _security_store(hass).setdefault("parent_pin_ip_failures", {})
    ip = _client_ip(request)
    now = dt_util.utcnow()
    record = store.get(ip)
    if record is None or (now - record["window"]).total_seconds() >= PARENT_PIN_IP_WINDOW_SECONDS:
        store[ip] = {"window": now, "count": 1}
        return
    record["count"] += 1


def parent_pin_ip_blocked(hass, request: web.Request) -> bool:
    store = _security_store(hass).setdefault("parent_pin_ip_failures", {})
    ip = _client_ip(request)
    record = store.get(ip)
    if record is None:
        return False
    if (dt_util.utcnow() - record["window"]).total_seconds() >= PARENT_PIN_IP_WINDOW_SECONDS:
        store.pop(ip, None)
        return False
    return record["count"] >= PARENT_PIN_IP_MAX_FAILURES


async def reject_unauthorized(hass, request: web.Request) -> None:
    """Count failures and raise HTTP 401 (Home Assistant may ip-ban repeat offenders)."""
    _record_integration_auth_failure(hass, request)
    try:
        from homeassistant.components.http.ban import process_wrong_login

        await process_wrong_login(request)
    except Exception:  # noqa: BLE001 — optional when http ban middleware is absent
        pass
    raise web.HTTPUnauthorized()


def browser_origin_allowed(request: web.Request) -> bool:
    """CSRF guard for browser POST endpoints (portal action, parent override)."""
    origin = request.headers.get("Origin", "")
    host = request.headers.get("Host", "")
    if origin and host:
        return origin.endswith(f"//{host}") or origin.split("://", 1)[-1] == host
    sec_fetch_site = request.headers.get("Sec-Fetch-Site", "")
    if sec_fetch_site in {"same-origin", "same-site"}:
        return True
    referer = request.headers.get("Referer", "")
    if referer and host and f"://{host}/" in referer:
        return True
    return False


async def read_json_object(request: web.Request) -> dict[str, Any]:
    if request.content_length is not None and request.content_length > MAX_JSON_BYTES:
        raise web.HTTPRequestEntityTooLarge(
            MAX_JSON_BYTES, request.content_length
        )
    try:
        raw = await request.content.read(MAX_JSON_BYTES + 1)
    except ValueError as err:
        raise web.HTTPBadRequest(text="Expected a JSON object") from err
    if len(raw) > MAX_JSON_BYTES:
        raise web.HTTPRequestEntityTooLarge(MAX_JSON_BYTES, len(raw))
    if not raw:
        return {}
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as err:
        raise web.HTTPBadRequest(text="Expected a JSON object") from err
    if not isinstance(payload, dict):
        raise web.HTTPBadRequest(text="Expected a JSON object")
    return payload


def portal_response_headers() -> dict[str, str]:
    return {
        **_PORTAL_SAFE_HEADERS,
        "Content-Security-Policy": (
            "default-src 'none'; style-src 'unsafe-inline'; "
            "script-src 'unsafe-inline'; connect-src 'self'; "
            "img-src data:; frame-ancestors 'none'; base-uri 'none'; "
            "form-action 'none'"
        ),
    }


def request_is_secure(request: web.Request) -> bool:
    return request.scheme == "https" or request.headers.get("X-Forwarded-Proto") == "https"
