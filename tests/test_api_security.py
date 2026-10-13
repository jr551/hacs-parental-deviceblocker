"""Security helpers and negative auth tests."""

from __future__ import annotations

import asyncio
import importlib.util
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

from aiohttp import web

ROOT = Path(__file__).resolve().parents[1]
INTEGRATION = ROOT / "custom_components" / "rowe_pc_blocker"
PKG = "custom_components.rowe_pc_blocker"


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    if name.startswith(PKG):
        module.__package__ = PKG
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


CONST = _load(f"{PKG}.const", INTEGRATION / "const.py")
SEC = _load(f"{PKG}.api_security", INTEGRATION / "api_security.py")


class _FakeRuntime:
    def __init__(self, device_id: str, api_key: str) -> None:
        self.device_id = device_id
        self.api_key = api_key


def _request(
    *,
    device_id: str = "child-pc",
    headers: dict | None = None,
    cookies: dict | None = None,
    remote: str = "203.0.113.10",
    origin: str | None = None,
    content: bytes = b"{}",
    content_length: int | None = None,
) -> SimpleNamespace:
    body = content
    headers = dict(headers or {})
    if origin:
        headers["Origin"] = origin
        headers["Host"] = "home.example"
    match_info = {"device_id": device_id}
    cookiejar = MagicMock()
    cookiejar.get = lambda name, default="": cookies.get(name, default) if cookies else default

    async def read(limit: int) -> bytes:
        return body[:limit]

    content_obj = SimpleNamespace(read=read)
    return SimpleNamespace(
        headers=headers,
        cookies=cookiejar,
        remote=remote,
        match_info=match_info,
        content=content_obj,
        content_length=content_length if content_length is not None else len(body),
        scheme="https",
    )


def _hass() -> MagicMock:
    hass = MagicMock()
    hass.data = {"rowe_pc_blocker": {"_api_security": {}}}
    return hass


class ApiSecurityTests(unittest.TestCase):
    def test_device_id_validation_rejects_enumeration_shapes(self) -> None:
        self.assertTrue(SEC.valid_device_id("child-pc"))
        self.assertFalse(SEC.valid_device_id("../admin"))
        self.assertFalse(SEC.valid_device_id(""))

    def test_api_key_entropy_requires_128_bits(self) -> None:
        self.assertTrue(SEC.api_key_meets_minimum_entropy(SEC.generate_api_key()))
        self.assertFalse(SEC.api_key_meets_minimum_entropy("short-key"))

    def test_wrong_key_is_rejected_for_known_device(self) -> None:
        hass = _hass()
        runtime = _FakeRuntime("child-pc", "correct-key-value-with-enough-entropy-here")
        request = _request(device_id="child-pc", headers={"X-Device-Blocker-Key": "wrong"})
        self.assertFalse(
            SEC.device_authorized(hass, runtime, "wrong", request, allow_cookie=False)
        )

    def test_another_devices_key_does_not_authorize(self) -> None:
        hass = _hass()
        runtime = _FakeRuntime("child-pc", "device-a-key-with-sufficient-entropy-12345")
        other_key = "device-b-key-with-sufficient-entropy-12345"
        request = _request(device_id="child-pc", headers={"X-Device-Blocker-Key": other_key})
        self.assertFalse(
            SEC.device_authorized(hass, runtime, other_key, request, allow_cookie=False)
        )

    def test_missing_key_is_rejected_even_when_device_exists(self) -> None:
        hass = _hass()
        runtime = _FakeRuntime("child-pc", "device-a-key-with-sufficient-entropy-12345")
        request = _request(device_id="child-pc")
        self.assertFalse(
            SEC.device_authorized(hass, runtime, "", request, allow_cookie=False)
        )

    def test_unknown_device_does_not_reveal_existence_via_authorize(self) -> None:
        hass = _hass()
        request = _request(
            device_id="missing-pc",
            headers={"X-Device-Blocker-Key": "probe-key-with-enough-entropy-123456"},
        )
        self.assertFalse(
            SEC.device_authorized(hass, None, "probe-key-with-enough-entropy-123456", request)
        )

    def test_portal_session_cookie_round_trip(self) -> None:
        runtime = _FakeRuntime("child-pc", "portal-device-key-with-enough-entropy")
        request = _request(device_id="child-pc")
        cookie_header = SEC.portal_session_cookie(request, runtime, secure=True)
        token = cookie_header.split("=", 1)[1].split(";", 1)[0]
        request.cookies.get = lambda name, default="": token if name == SEC.PORTAL_COOKIE_NAME else default
        self.assertTrue(SEC.portal_cookie_valid(runtime, request))

    def test_integration_auth_throttle_blocks_hot_ips(self) -> None:
        hass = MagicMock()
        hass.data = {"rowe_pc_blocker": {"_api_security": {}}}
        request = _request(remote="198.51.100.44")
        for _ in range(SEC.INTEGRATION_AUTH_MAX_FAILURES):
            SEC._record_integration_auth_failure(hass, request)
        self.assertTrue(SEC.integration_auth_blocked(hass, request))

    def test_parent_pin_ip_throttle(self) -> None:
        hass = MagicMock()
        hass.data = {"rowe_pc_blocker": {"_api_security": {}}}
        request = _request(remote="198.51.100.55")
        for _ in range(SEC.PARENT_PIN_IP_MAX_FAILURES):
            SEC.record_parent_pin_failure(hass, request)
        self.assertTrue(SEC.parent_pin_ip_blocked(hass, request))

    def test_browser_csrf_requires_origin(self) -> None:
        bad = _request(device_id="child-pc")
        good = _request(device_id="child-pc", origin="https://home.example")
        self.assertFalse(SEC.browser_origin_allowed(bad))
        self.assertTrue(SEC.browser_origin_allowed(good))

    def test_read_json_object_rejects_oversize_body(self) -> None:
        request = _request(content=b"x" * (SEC.MAX_JSON_BYTES + 1), content_length=SEC.MAX_JSON_BYTES + 1)

        async def run() -> None:
            with self.assertRaises(web.HTTPRequestEntityTooLarge):
                await SEC.read_json_object(request)

        asyncio.run(run())


if __name__ == "__main__":
    unittest.main()
