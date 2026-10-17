"""Diagnostics: the device state a support case needs, and no secrets.

The integration's diagnostics are the one place a parent may paste into a
public issue, so the device key, the VNC password, the provider key, and the
parent PIN must never appear in the output.
"""

from __future__ import annotations

import json
import unittest

import ha_stub
from ha_stub import FakeConfigEntry, FakeHass

CONST = ha_stub.load("const")
DIAGNOSTICS = ha_stub.load("diagnostics")
EVIDENCE = ha_stub.load("evidence")
NOTIFICATIONS = ha_stub.load("notifications")
PENDING = ha_stub.load("pending")
RUNTIME = ha_stub.load("runtime")

PHONE_DATA = {
    CONST.CONF_DEVICE_ID: "casey-phone",
    CONST.CONF_DEVICE_NAME: "Casey's phone",
    CONST.CONF_DEVICE_TYPE: CONST.DEVICE_TYPE_ANDROID,
    CONST.CONF_WINDOWS_USERNAME: "Casey",
    CONST.CONF_API_KEY: "the-device-key",
}

SECRET_OPTIONS = {
    CONST.CONF_VNC_PASSWORD: "the-vnc-password",
    CONST.CONF_KILO_API_KEY: "the-provider-key",
    CONST.CONF_PARENT_PIN: "1234",
}

JPEG = b"\xff\xd8\xff\xe0" + b"photo" * 10


class DiagnosticsTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.hass = FakeHass()
        self.runtime = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id="casey-phone",
            device_name="Casey's phone",
            windows_username="Casey",
            api_key="the-device-key",
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        self.hass.data.setdefault(CONST.DOMAIN, {})["entry-1"] = self.runtime
        self.entry = FakeConfigEntry(
            dict(PHONE_DATA), entry_id="entry-1", options=dict(SECRET_OPTIONS)
        )

    async def _diagnostics(self) -> dict:
        return await DIAGNOSTICS.async_get_config_entry_diagnostics(
            self.hass, self.entry
        )

    async def test_no_secret_survives_into_the_payload(self) -> None:
        payload = await self._diagnostics()
        self.assertEqual(
            payload["entry"]["data"][CONST.CONF_API_KEY], "**REDACTED**"
        )
        for key in SECRET_OPTIONS:
            self.assertEqual(payload["entry"]["options"][key], "**REDACTED**")

        dumped = json.dumps(payload)
        for secret in ("the-device-key", *SECRET_OPTIONS.values()):
            self.assertNotIn(secret, dumped)

    async def test_the_challenge_and_its_evidence_are_covered(self) -> None:
        self.assertIsNone((await self._diagnostics())["runtime"]["challenge"])

        await self.runtime.async_send_challenge("I have brushed my teeth")
        challenge = (await self._diagnostics())["runtime"]["challenge"]
        self.assertEqual(challenge["state"], "pending")
        self.assertEqual(challenge["phrase"], "I have brushed my teeth")
        self.assertEqual(challenge["id"], self.runtime.challenge_id)
        self.assertEqual(challenge["evidence"], {"stored": False, "expires_at": None})

        await EVIDENCE.async_save_evidence(
            self.hass, self.runtime.device_id, self.runtime.challenge_id, JPEG
        )
        challenge = (await self._diagnostics())["runtime"]["challenge"]
        self.assertTrue(challenge["evidence"]["stored"])
        self.assertTrue(challenge["evidence"]["expires_at"])

    async def test_the_allowlist_and_the_unblock_requests_are_covered(self) -> None:
        self.runtime.allowed_apps = "com.whatsapp, com.android.dialer"
        status, body = await PENDING.async_create_unblock_request(
            self.hass, self.runtime, "com.roblox.client", "Roblox"
        )
        self.assertEqual(status, 200)

        runtime = (await self._diagnostics())["runtime"]
        self.assertEqual(runtime["allowed_apps"], ["com.whatsapp", "com.android.dialer"])
        self.assertEqual(
            runtime["unblock_requests"],
            [
                {
                    "request_id": body["request_id"],
                    "package": "com.roblox.client",
                    "label": "Roblox",
                    "requested_at": runtime["unblock_requests"][0]["requested_at"],
                }
            ],
        )

    async def test_the_block_state_is_covered(self) -> None:
        await self.runtime.async_set_blocked(True)
        runtime = (await self._diagnostics())["runtime"]
        self.assertTrue(runtime["blocked"])
        self.assertFalse(runtime["effective_blocked"])
        self.assertEqual(runtime["block_reason"], "grace_period")
        self.assertTrue(runtime["block_requested_at"])
        self.assertTrue(runtime["enforce_at"])

    async def test_the_notification_text_never_reaches_the_payload(self) -> None:
        self.assertIsNone((await self._diagnostics())["runtime"]["notifications"])

        self.runtime.notifications = NOTIFICATIONS.parse_dump(
            {
                "date": "2026-10-17",
                "count": 3,
                "truncated": False,
                "items": [
                    {
                        "package": "com.whatsapp",
                        "app": "WhatsApp",
                        "title": "SECRET-TITLE",
                        "text": "SECRET-BODY",
                        "posted_at": "2026-10-17T11:00:00+00:00",
                    }
                ],
            },
            ha_stub.utcnow(),
        )
        notifications = (await self._diagnostics())["runtime"]["notifications"]
        self.assertEqual(notifications["date"], "2026-10-17")
        self.assertEqual(notifications["count"], 3)
        self.assertEqual(notifications["items_stored"], 1)
        self.assertEqual(
            notifications["apps"],
            [{"package": "com.whatsapp", "app": "WhatsApp", "count": 1}],
        )

        dumped = json.dumps(await self._diagnostics())
        self.assertNotIn("SECRET-TITLE", dumped)
        self.assertNotIn("SECRET-BODY", dumped)

    async def test_an_entry_with_no_runtime_still_reports_the_redacted_entry(self) -> None:
        self.hass.data[CONST.DOMAIN].pop("entry-1")
        payload = await self._diagnostics()
        self.assertEqual(set(payload), {"entry"})
        self.assertEqual(
            payload["entry"]["data"][CONST.CONF_API_KEY], "**REDACTED**"
        )


if __name__ == "__main__":
    unittest.main()
