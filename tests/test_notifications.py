"""The daily notification dump: ingest, caps, storage, and the sensor.

The endpoint is exercised through the real view class against the stand-ins in
``ha_stub.py``, so the status codes asserted here are the ones a phone sees.
"""

from __future__ import annotations

import json
import unittest
from datetime import datetime, timezone

import ha_stub
from ha_stub import FakeHass, FakeRequest

API = ha_stub.load("api")
CONST = ha_stub.load("const")
NOTIFICATIONS = ha_stub.load("notifications")
PROTOCOL = ha_stub.load("protocol_surface")
RUNTIME = ha_stub.load("runtime")
SENSOR = ha_stub.load("sensor")

DEVICE_KEY = "the-device-key"
NOW = datetime(2026, 10, 17, 12, 0, tzinfo=timezone.utc)


def _item(
    package: str = "com.whatsapp",
    app: str = "WhatsApp",
    title: str = "Mum",
    text: str = "Tea is ready",
    posted_at: str | None = "2026-10-17T11:00:00+00:00",
) -> dict:
    return {
        "package": package,
        "app": app,
        "title": title,
        "text": text,
        "posted_at": posted_at,
    }


def _dump(
    date: str | None = "2026-10-17",
    count: int | None = None,
    truncated: bool = False,
    items: list | None = None,
) -> dict:
    items = [_item()] if items is None else items
    return {
        "date": date,
        "count": len(items) if count is None else count,
        "truncated": truncated,
        "items": items,
    }


class NotificationTestCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.hass = FakeHass()
        self.runtime = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id="casey-phone",
            device_name="Casey's phone",
            windows_username="Casey",
            api_key=DEVICE_KEY,
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        self.hass.data.setdefault(CONST.DOMAIN, {})["entry-1"] = self.runtime
        # The entry setup loads the runtime store before any platform is set
        # up, so a dump posted afterwards is persisted.
        await self.runtime.async_load()
        self.view = API.PcNotificationsView()

    async def _post(self, payload: dict | None, *, raw: bytes | None = None):
        body = raw if raw is not None else json.dumps(payload).encode()
        request = FakeRequest(
            self.hass, body=body, headers={"X-Device-Blocker-Key": DEVICE_KEY}
        )
        return await self.view.post(request, "casey-phone")


class NotificationsEndpointTests(NotificationTestCase):
    async def test_a_valid_dump_is_stored_and_summarised(self) -> None:
        items = [
            _item(title="Mum", posted_at="2026-10-17T11:00:00+00:00"),
            _item(
                package="com.roblox.client",
                app="Roblox",
                title="Robux",
                posted_at="2026-10-17T12:00:00+00:00",
            ),
            _item(title="Dad", posted_at="2026-10-17T10:00:00+00:00"),
        ]
        response = await self._post(_dump(count=137, items=items))
        self.assertEqual(response.status, 200)
        self.assertEqual(response.data, PROTOCOL.OK_BODY)

        dump = self.runtime.notifications
        self.assertEqual(dump.date, "2026-10-17")
        self.assertEqual(dump.count, 137)
        self.assertFalse(dump.truncated)
        self.assertEqual(
            dump.apps,
            [
                {"package": "com.whatsapp", "app": "WhatsApp", "count": 2},
                {"package": "com.roblox.client", "app": "Roblox", "count": 1},
            ],
        )
        # Newest first, whatever order the phone sent them in.
        self.assertEqual([item.title for item in dump.items], ["Robux", "Mum", "Dad"])

    async def test_an_oversize_dump_is_refused_with_413(self) -> None:
        body = json.dumps(
            _dump(items=[_item(text="x" * 1000) for _ in range(300)])
        ).encode()
        self.assertGreater(len(body), NOTIFICATIONS.MAX_DUMP_BYTES)

        response = await self._post(None, raw=body)
        self.assertEqual(response.status, 413)
        self.assertIn("error", response.data)
        self.assertIsNone(self.runtime.notifications)

    async def test_a_body_that_is_not_a_json_object_is_refused_with_400(self) -> None:
        response = await self._post(None, raw=b'["not", "an", "object"]')
        self.assertEqual(response.status, 400)
        self.assertIn("error", response.data)

    async def test_a_missing_or_malformed_date_is_refused_with_400(self) -> None:
        for payload in (
            {"count": 3, "items": []},
            _dump(date="17/10/2026"),
            _dump(date="2026-1-1"),
            _dump(date=None),
        ):
            response = await self._post(payload)
            self.assertEqual(response.status, 400, payload)
            self.assertIn("error", response.data)
        self.assertIsNone(self.runtime.notifications)

    async def test_an_older_day_is_refused(self) -> None:
        await self._post(_dump(date="2026-10-17", count=5))
        response = await self._post(_dump(date="2026-10-16", count=99))

        # The phone is told the dump was accepted; the newer day is kept.
        self.assertEqual(response.status, 200)
        self.assertEqual(self.runtime.notifications.date, "2026-10-17")
        self.assertEqual(self.runtime.notifications.count, 5)

    async def test_the_same_day_can_be_retried(self) -> None:
        await self._post(_dump(date="2026-10-17", count=5))
        await self._post(_dump(date="2026-10-17", count=9))
        self.assertEqual(self.runtime.notifications.count, 9)

    async def test_a_newer_day_replaces_the_stored_one(self) -> None:
        await self._post(_dump(date="2026-10-16", count=5))
        await self._post(_dump(date="2026-10-17", count=9))
        self.assertEqual(self.runtime.notifications.date, "2026-10-17")
        self.assertEqual(self.runtime.notifications.count, 9)

    async def test_a_dump_with_no_items_is_still_a_dump(self) -> None:
        response = await self._post(_dump(count=0, items=[]))
        self.assertEqual(response.status, 200)
        self.assertEqual(self.runtime.notifications.count, 0)
        self.assertEqual(self.runtime.notifications.items, [])
        self.assertEqual(self.runtime.notifications.apps, [])


class NotificationCapTests(unittest.TestCase):
    def test_the_item_count_is_capped(self) -> None:
        # Small items, so the count cap is what binds rather than the size
        # budget the next test covers.
        items = [
            _item(package="a", app="a", title="a", text="a", posted_at=None)
            for _ in range(500)
        ]
        dump = NOTIFICATIONS.parse_dump(_dump(count=500, items=items), NOW)
        self.assertEqual(len(dump.items), NOTIFICATIONS.MAX_SENSOR_ITEMS)
        # The state is the phone's own count for the day, not the shown items.
        self.assertEqual(dump.count, 500)

    def test_the_items_stay_inside_their_size_budget(self) -> None:
        items = [
            _item(title="t" * 120, text="x" * 200, posted_at=None) for _ in range(500)
        ]
        dump = NOTIFICATIONS.parse_dump(_dump(count=500, items=items), NOW)
        self.assertLess(len(dump.items), NOTIFICATIONS.MAX_SENSOR_ITEMS)
        size = len(json.dumps([item.as_dict() for item in dump.items]))
        self.assertLessEqual(size, NOTIFICATIONS.MAX_ITEMS_ATTRIBUTE_BYTES)

    def test_the_app_summary_is_capped_and_most_used_first(self) -> None:
        items = [
            _item(package=f"com.app{index:03d}", app=f"App {index}")
            for index in range(60)
        ]
        items += [_item(package="com.app000", app="App 0")] * 3
        dump = NOTIFICATIONS.parse_dump(_dump(count=len(items), items=items), NOW)

        self.assertEqual(len(dump.apps), NOTIFICATIONS.MAX_SENSOR_APPS)
        self.assertEqual(
            dump.apps[0], {"package": "com.app000", "app": "App 0", "count": 4}
        )
        self.assertEqual(
            [app["count"] for app in dump.apps[1:]],
            [1] * (NOTIFICATIONS.MAX_SENSOR_APPS - 1),
        )

    def test_one_notification_cannot_dominate_the_payload(self) -> None:
        dump = NOTIFICATIONS.parse_dump(
            _dump(items=[_item(title="t" * 5000, text="x" * 5000)]), NOW
        )
        stored = dump.items[0]
        self.assertEqual(len(stored.title), NOTIFICATIONS.MAX_TITLE_LENGTH)
        self.assertEqual(len(stored.text), NOTIFICATIONS.MAX_TEXT_LENGTH)

    def test_a_malformed_item_is_dropped_rather_than_refusing_the_day(self) -> None:
        dump = NOTIFICATIONS.parse_dump(
            _dump(items=[_item(), "not an item", {"package": "com.whatsapp"}]),
            NOW,
        )
        self.assertEqual(len(dump.items), 2)
        self.assertEqual(dump.items[1].title, "")


class NotificationStorageTests(NotificationTestCase):
    async def test_the_dump_survives_a_restart(self) -> None:
        await self._post(_dump(count=137, truncated=True))
        await self.runtime.async_save()

        restarted = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id="casey-phone",
            device_name="Casey's phone",
            windows_username="Casey",
            api_key=DEVICE_KEY,
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        await restarted.async_load()
        self.assertEqual(restarted.notifications.date, "2026-10-17")
        self.assertEqual(restarted.notifications.count, 137)
        self.assertTrue(restarted.notifications.truncated)
        self.assertEqual(restarted.notifications.items[0].title, "Mum")
        self.assertEqual(
            restarted.notifications.apps,
            [{"package": "com.whatsapp", "app": "WhatsApp", "count": 1}],
        )

    async def test_a_stored_dump_with_no_date_is_ignored(self) -> None:
        self.assertIsNone(NOTIFICATIONS.NotificationDump.from_dict({"count": 3}))
        self.assertIsNone(NOTIFICATIONS.NotificationDump.from_dict("nonsense"))


class NotificationsSensorTests(NotificationTestCase):
    async def test_the_sensor_reports_the_day_and_the_items(self) -> None:
        sensor = SENSOR.NotificationsSensor(self.runtime)
        self.assertIsNone(sensor.native_value)
        self.assertEqual(
            sensor.extra_state_attributes,
            {"date": None, "truncated": None, "apps": [], "items": [], "received_at": None},
        )

        await self._post(_dump(count=137, truncated=True))
        self.assertEqual(sensor.native_value, 137)
        attributes = sensor.extra_state_attributes
        self.assertEqual(attributes["date"], "2026-10-17")
        self.assertTrue(attributes["truncated"])
        self.assertEqual(
            attributes["apps"],
            [{"package": "com.whatsapp", "app": "WhatsApp", "count": 1}],
        )
        self.assertEqual(attributes["items"][0]["title"], "Mum")
        self.assertEqual(
            attributes["received_at"],
            self.runtime.notifications.received_at.isoformat(),
        )


if __name__ == "__main__":
    unittest.main()
