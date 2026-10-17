"""On-demand notification dump: the request, the answer, and the button.

The phone still posts a dump at midnight on its own. A parent can also ask for
the day's dump now, and the answer names the request it answers so a late or
superseded answer can never be mistaken for the current one.

The endpoints are exercised through the real view classes against the
stand-ins in ``ha_stub.py``, so the status codes asserted here are the ones a
phone sees.
"""

from __future__ import annotations

import json
import unittest
from datetime import timedelta

import ha_stub
from ha_stub import FakeConfigEntry, FakeHass, FakeRequest, object_id

API = ha_stub.load("api")
BUTTON = ha_stub.load("button")
CONST = ha_stub.load("const")
NOTIFICATIONS = ha_stub.load("notifications")
PROTOCOL = ha_stub.load("protocol_surface")
RUNTIME = ha_stub.load("runtime")
SENSOR = ha_stub.load("sensor")

DEVICE_KEY = "the-device-key"
PHONE = "casey-phone"


def _dump(
    date: str = "2026-10-17",
    count: int = 2,
    request_id: str | None = None,
) -> dict:
    payload = {
        "date": date,
        "count": count,
        "truncated": False,
        "items": [
            {
                "package": "com.roblox.client",
                "app": "Roblox",
                "title": "Robux",
                "text": "Your daily reward is ready",
                "posted_at": "2026-10-17T16:05:00+00:00",
            }
        ],
    }
    if request_id is not None:
        payload["request_id"] = request_id
    return payload


class NotificationRequestTestCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.hass = FakeHass()
        self.runtime = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id=PHONE,
            device_name="Casey's phone",
            windows_username="Casey",
            api_key=DEVICE_KEY,
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        self.hass.data.setdefault(CONST.DOMAIN, {})["entry-1"] = self.runtime
        self.hass.config_entries.entries.append(FakeConfigEntry({}, entry_id="entry-1"))
        await self.runtime.async_load()
        self.view = API.PcNotificationsView()
        self.state_view = API.PcStateView()

    def _request(self, payload=None, *, raw: bytes | None = None) -> FakeRequest:
        body = raw if raw is not None else json.dumps(payload or {}).encode()
        return FakeRequest(
            self.hass, body=body, headers={"X-Device-Blocker-Key": DEVICE_KEY}
        )

    async def _post(self, payload=None, *, raw: bytes | None = None):
        return await self.view.post(self._request(payload, raw=raw), PHONE)

    async def _state(self):
        return await self.state_view.get(self._request(), PHONE)

    def _expire(self) -> None:
        self.runtime.notification_requested_at = ha_stub.utcnow() - timedelta(
            seconds=NOTIFICATIONS.NOTIFICATION_REQUEST_TTL_SECONDS + 1
        )


class NotificationRequestButtonTests(NotificationRequestTestCase):
    async def test_the_button_exists_for_a_phone_and_not_a_pc(self) -> None:
        phone_entities: list = []
        await BUTTON.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id="entry-1"), phone_entities.extend
        )
        button = next(
            entity
            for entity in phone_entities
            if isinstance(entity, BUTTON.RequestNotificationsButton)
        )
        self.assertEqual(button.name, "Request notifications")
        self.assertEqual(button._attr_icon, "mdi:bell-ring")
        self.assertEqual(button.unique_id, "casey-phone_request_notifications")
        self.assertEqual(object_id(button), "caseys_phone_request_notifications")

        pc_runtime = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-2",
            device_id="casey-pc",
            device_name="Casey's PC",
            windows_username="Casey",
            api_key="existing-key",
            device_type=CONST.DEVICE_TYPE_WINDOWS,
        )
        self.hass.data[CONST.DOMAIN]["entry-2"] = pc_runtime
        pc_entities: list = []
        await BUTTON.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id="entry-2"), pc_entities.extend
        )
        self.assertFalse(
            any(
                isinstance(entity, BUTTON.RequestNotificationsButton)
                for entity in pc_entities
            )
        )

    async def test_pressing_the_button_puts_a_live_request_on_the_payload(self) -> None:
        self.assertIsNone(self.runtime.notification_request_payload)
        self.assertIsNone((await self._state()).data["notification_request"])

        button = BUTTON.RequestNotificationsButton(self.runtime)
        await button.async_press()
        request_id = self.runtime.notification_request_id
        self.assertEqual(len(request_id), 32)
        self.assertEqual(request_id, request_id.lower())

        payload = self.runtime.notification_request_payload
        self.assertEqual(set(payload), PROTOCOL.NOTIFICATION_REQUEST_PAYLOAD_KEYS)
        self.assertEqual(payload["id"], request_id)
        self.assertEqual(
            payload["requested_at"],
            self.runtime.notification_requested_at.isoformat(),
        )
        self.assertEqual((await self._state()).data["notification_request"], payload)

    async def test_a_second_press_replaces_the_request(self) -> None:
        first = await self.runtime.async_request_notifications()
        second = await self.runtime.async_request_notifications()
        self.assertNotEqual(first, second)
        self.assertEqual(self.runtime.notification_request_payload["id"], second)

    async def test_an_expired_request_is_dropped(self) -> None:
        await self.runtime.async_request_notifications()
        self._expire()

        # It stops riding the payload at once, and the next poll clears it.
        self.assertIsNone(self.runtime.notification_request_payload)
        self.assertTrue(await self.runtime.async_expire_notification_request())
        self.assertEqual(self.runtime.notification_request_id, "")
        self.assertIsNone((await self._state()).data["notification_request"])

        # A live request is left alone.
        await self.runtime.async_request_notifications()
        self.assertFalse(await self.runtime.async_expire_notification_request())
        self.assertTrue(self.runtime.notification_request_id)

    async def test_the_pending_request_survives_a_restart(self) -> None:
        request_id = await self.runtime.async_request_notifications()
        restarted = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id=PHONE,
            device_name="Casey's phone",
            windows_username="Casey",
            api_key=DEVICE_KEY,
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        await restarted.async_load()
        self.assertEqual(restarted.notification_request_id, request_id)
        self.assertEqual(restarted.notification_request_payload["id"], request_id)


class NotificationAnswerTests(NotificationRequestTestCase):
    async def test_a_dump_without_a_request_id_is_still_the_daily_flush(self) -> None:
        response = await self._post(_dump())
        self.assertEqual(response.status, 200)
        self.assertEqual(response.data, PROTOCOL.OK_BODY)
        self.assertEqual(self.runtime.notifications.date, "2026-10-17")

        # A flush is not an answer, so a parent's ask is still waiting.
        request_id = await self.runtime.async_request_notifications()
        await self._post(_dump(count=3))
        self.assertEqual(self.runtime.notification_request_id, request_id)
        self.assertEqual(self.runtime.notifications.count, 3)

    async def test_an_answer_with_the_live_id_is_accepted_and_clears_the_request(
        self,
    ) -> None:
        request_id = await self.runtime.async_request_notifications()
        response = await self._post(_dump(count=7, request_id=request_id))
        self.assertEqual(response.status, 200)
        self.assertEqual(response.data, PROTOCOL.OK_BODY)

        self.assertEqual(self.runtime.notifications.count, 7)
        self.assertEqual(self.runtime.notification_request_id, "")
        self.assertIsNone(self.runtime.notification_requested_at)
        self.assertIsNone(self.runtime.notification_request_payload)
        self.assertIsNone((await self._state()).data["notification_request"])

        # The request is cleared in the store as well as in memory.
        restarted = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id=PHONE,
            device_name="Casey's phone",
            windows_username="Casey",
            api_key=DEVICE_KEY,
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        await restarted.async_load()
        self.assertEqual(restarted.notification_request_id, "")

    async def test_a_stale_request_id_is_refused_with_404(self) -> None:
        response = await self._post(_dump(request_id="b" * 32))
        self.assertEqual(response.status, 404)
        self.assertIn("error", response.data)
        self.assertIsNone(self.runtime.notifications)

    async def test_an_answer_for_a_superseded_request_is_refused(self) -> None:
        first = await self.runtime.async_request_notifications()
        second = await self.runtime.async_request_notifications()

        response = await self._post(_dump(request_id=first))
        self.assertEqual(response.status, 404)
        self.assertIn("error", response.data)
        self.assertIsNone(self.runtime.notifications)
        # The newer request is still the one the phone should answer.
        self.assertEqual(self.runtime.notification_request_id, second)

        response = await self._post(_dump(request_id=second))
        self.assertEqual(response.status, 200)
        self.assertEqual(self.runtime.notifications.date, "2026-10-17")

    async def test_an_answer_after_expiry_is_refused_with_404(self) -> None:
        request_id = await self.runtime.async_request_notifications()
        self._expire()
        response = await self._post(_dump(request_id=request_id))
        self.assertEqual(response.status, 404)
        self.assertIsNone(self.runtime.notifications)

    async def test_the_answer_adds_only_the_documented_request_id_field(self) -> None:
        self.assertEqual(
            set(_dump(request_id="c" * 32)),
            PROTOCOL.NOTIFICATION_REQUEST_KEYS | PROTOCOL.NOTIFICATION_ANSWER_KEYS,
        )


class NotificationSensorTests(NotificationRequestTestCase):
    async def test_the_answered_dump_reaches_the_notifications_sensor(self) -> None:
        sensor = SENSOR.NotificationsSensor(self.runtime)
        sensor.hass = self.hass
        self.assertIsNone(sensor.native_value)

        request_id = await self.runtime.async_request_notifications()
        await self._post(_dump(count=9, request_id=request_id))
        self.assertEqual(sensor.native_value, "Roblox 1")
        self.assertEqual(sensor.extra_state_attributes["count"], 9)
        self.assertEqual(sensor.extra_state_attributes["date"], "2026-10-17")


if __name__ == "__main__":
    unittest.main()
