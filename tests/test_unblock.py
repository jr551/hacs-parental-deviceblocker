"""The app-unblock contract: what a blocked phone asks, and what a parent answers.

The HTTP view is a thin wrapper around ``pending.async_create_unblock_request``
(the route itself is pinned in ``test_protocol.py``), so these tests drive the
rules, the store, and the entities the dashboard shows.
"""

from __future__ import annotations

import unittest
from datetime import date, datetime, timedelta

import ha_stub
from ha_stub import FakeConfigEntry, FakeHass, FakeRequest, object_id

API = ha_stub.load("api")
CONST = ha_stub.load("const")
APPS = ha_stub.load("apps")
PENDING = ha_stub.load("pending")
PROVISION = ha_stub.load("provision")
RUNTIME = ha_stub.load("runtime")
SENSOR = ha_stub.load("sensor")
BUTTON = ha_stub.load("button")

NOW = ha_stub.utcnow()
EXPIRED = timedelta(seconds=PROVISION.PENDING_TTL_SECONDS + 1)


class UnblockStoreTests(unittest.TestCase):
    """The store rules, without Home Assistant."""

    def setUp(self) -> None:
        self.store = PROVISION.PendingUnblockStore()

    def test_a_request_is_created_and_listed_for_its_device(self) -> None:
        request = self.store.create("casey-phone", "com.roblox.client", "Roblox", NOW)
        self.assertEqual(len(request.request_id), 32)
        self.assertEqual(self.store.list(NOW), [request])
        self.assertEqual(self.store.for_device("casey-phone", NOW), [request])
        self.assertEqual(self.store.for_device("other-phone", NOW), [])
        self.assertEqual(self.store.get(request.request_id, NOW), request)

    def test_one_request_per_device_and_package(self) -> None:
        self.store.create("casey-phone", "com.roblox.client", "Roblox", NOW)
        found = self.store.pending_for("casey-phone", "com.roblox.client", NOW)
        self.assertIsNotNone(found)
        self.assertIsNone(self.store.pending_for("casey-phone", "com.other.app", NOW))
        self.assertIsNone(
            self.store.pending_for("other-phone", "com.roblox.client", NOW)
        )

    def test_an_expired_request_is_pruned_by_every_read(self) -> None:
        request = self.store.create("casey-phone", "com.roblox.client", "Roblox", NOW)
        later = NOW + EXPIRED
        self.assertEqual(self.store.list(later), [])
        self.assertIsNone(self.store.get(request.request_id, later))
        self.assertEqual(self.store.for_device("casey-phone", later), [])
        self.assertIsNone(
            self.store.pending_for("casey-phone", "com.roblox.client", later)
        )

    def test_removing_an_answered_request_reports_whether_it_was_live(self) -> None:
        request = self.store.create("casey-phone", "com.roblox.client", "Roblox", NOW)
        self.assertTrue(self.store.remove(request.request_id, NOW))
        self.assertFalse(self.store.remove(request.request_id, NOW))
        self.assertEqual(self.store.list(NOW), [])

    def test_a_stored_request_survives_a_restart(self) -> None:
        request = self.store.create("casey-phone", "com.roblox.client", "Roblox", NOW)
        reloaded = PROVISION.PendingUnblockStore()
        reloaded.load(self.store.dump())
        self.assertEqual(reloaded.list(NOW), [request])

    def test_an_unusable_stored_record_is_dropped(self) -> None:
        store = PROVISION.PendingUnblockStore()
        store.load(
            {
                "unblock_requests": [
                    "not a dict",
                    {"request_id": "a" * 32},
                    {
                        "request_id": "b" * 32,
                        "device_id": "casey-phone",
                        "package": "com.roblox.client",
                        "requested_at": "not a date",
                    },
                ]
            }
        )
        self.assertEqual(store.list(NOW), [])

    def test_a_stored_timestamp_without_a_zone_is_read_as_utc(self) -> None:
        store = PROVISION.PendingUnblockStore()
        store.load(
            {
                "unblock_requests": [
                    {
                        "request_id": "c" * 32,
                        "device_id": "casey-phone",
                        "package": "com.roblox.client",
                        "label": "Roblox",
                        "requested_at": NOW.replace(tzinfo=None).isoformat(),
                    }
                ]
            }
        )
        self.assertEqual(len(store.list(NOW)), 1)


class UnblockRequestTests(unittest.IsolatedAsyncioTestCase):
    """What the phone is answered, and what approving does."""

    def setUp(self) -> None:
        self.hass = FakeHass()
        self.runtime = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id="casey-phone",
            device_name="Casey's phone",
            windows_username="Casey",
            api_key="existing-key",
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        self.hass.data.setdefault(CONST.DOMAIN, {})["entry-1"] = self.runtime

    async def _request(self, package: str = "com.roblox.client", label: str = "Roblox"):
        return await PENDING.async_create_unblock_request(
            self.hass, self.runtime, package, label
        )

    async def test_a_valid_request_is_recorded_once_and_persisted(self) -> None:
        status_code, body = await self._request()
        self.assertEqual(status_code, 200)
        self.assertEqual(body["status"], "pending")
        self.assertEqual(len(body["request_id"]), 32)

        requests = PENDING.unblock_requests(self.hass)
        self.assertEqual(len(requests), 1)
        self.assertEqual(requests[0].request_id, body["request_id"])
        self.assertEqual(requests[0].device_id, "casey-phone")
        self.assertEqual(requests[0].package, "com.roblox.client")
        self.assertEqual(requests[0].label, "Roblox")

        # Both kinds of request share the one storage file.
        stored = self.hass.storage[PENDING.STORE_KEY]
        self.assertEqual(len(stored["unblock_requests"]), 1)
        self.assertEqual(stored["requests"], [])

    async def test_a_second_request_for_the_same_app_is_a_conflict(self) -> None:
        _status, body = await self._request()
        status_code, answer = await self._request()
        self.assertEqual(status_code, 409)
        self.assertIn("error", answer)
        self.assertEqual(
            [request.request_id for request in PENDING.unblock_requests(self.hass)],
            [body["request_id"]],
        )

    async def test_an_app_already_on_the_parent_list_is_a_conflict(self) -> None:
        self.runtime.allowed_apps = "com.whatsapp, com.roblox.client"
        status_code, answer = await self._request()
        self.assertEqual(status_code, 409)
        self.assertIn("error", answer)
        self.assertEqual(PENDING.unblock_requests(self.hass), [])

    async def test_an_app_in_the_built_in_defaults_is_a_conflict(self) -> None:
        status_code, _answer = await self._request("com.whatsapp", "WhatsApp")
        self.assertEqual(status_code, 409)
        self.assertEqual(PENDING.unblock_requests(self.hass), [])

    async def test_a_package_that_is_not_an_android_name_is_refused(self) -> None:
        for package in ("", "not a package", "com.roblox.client; rm -rf /", "1com.bad"):
            status_code, answer = await self._request(package)
            self.assertEqual(status_code, 400, package)
            self.assertIn("error", answer)
        self.assertEqual(PENDING.unblock_requests(self.hass), [])

    async def test_approving_allows_the_app_and_clears_the_request(self) -> None:
        _status, body = await self._request()
        approved = await PENDING.async_approve_unblock_request(
            self.hass, body["request_id"]
        )
        self.assertTrue(approved)
        self.assertEqual(PENDING.unblock_requests(self.hass), [])
        self.assertEqual(
            APPS.parse_allowed_apps(self.runtime.allowed_apps),
            [*APPS.DEFAULT_ALLOWED_APPS, "com.roblox.client"],
        )
        self.assertEqual(self.hass.storage[PENDING.STORE_KEY]["unblock_requests"], [])

    async def test_approving_keeps_a_parent_edited_list(self) -> None:
        self.runtime.allowed_apps = "com.whatsapp"
        _status, body = await self._request()
        await PENDING.async_approve_unblock_request(self.hass, body["request_id"])
        self.assertEqual(self.runtime.allowed_apps, "com.whatsapp, com.roblox.client")

    async def test_rejecting_clears_the_request_without_allowing_the_app(self) -> None:
        _status, body = await self._request()
        rejected = await PENDING.async_reject_unblock_request(
            self.hass, body["request_id"]
        )
        self.assertTrue(rejected)
        self.assertEqual(PENDING.unblock_requests(self.hass), [])
        self.assertEqual(self.runtime.allowed_apps, "")

    async def test_an_expired_request_cannot_be_approved(self) -> None:
        _status, body = await self._request()
        request = PENDING.unblock_store(self.hass).get(body["request_id"], NOW)
        request.requested_at = ha_stub.utcnow() - EXPIRED
        approved = await PENDING.async_approve_unblock_request(
            self.hass, body["request_id"]
        )
        self.assertFalse(approved)
        self.assertEqual(self.runtime.allowed_apps, "")
        self.assertEqual(PENDING.unblock_requests(self.hass), [])

    async def test_approving_a_request_for_a_removed_device_changes_nothing(self) -> None:
        _status, body = await self._request()
        self.hass.data[CONST.DOMAIN].pop("entry-1")
        approved = await PENDING.async_approve_unblock_request(
            self.hass, body["request_id"]
        )
        self.assertFalse(approved)
        self.assertEqual(len(PENDING.unblock_requests(self.hass)), 1)

    async def test_a_full_allowlist_keeps_the_request_pending(self) -> None:
        full = ", ".join(f"com.example.app{index:02d}" for index in range(13))
        self.runtime.allowed_apps = full
        _status, body = await self._request()
        approved = await PENDING.async_approve_unblock_request(
            self.hass, body["request_id"]
        )
        self.assertFalse(approved)
        self.assertEqual(self.runtime.allowed_apps, full)
        self.assertEqual(len(PENDING.unblock_requests(self.hass)), 1)

    def _forget_daily_limit(self) -> None:
        """Open the daily gate so the pending cap can be exercised.

        One request a day is the rule production hits first, so the cap is
        only reachable by pretending the last ask was on an earlier day.
        """
        self.runtime.unblock_request_day = "2000-01-01"

    async def _fill(self) -> list[str]:
        request_ids = []
        for index in range(PROVISION.MAX_PENDING_UNBLOCK_REQUESTS):
            self._forget_daily_limit()
            status_code, body = await self._request(f"com.app{index}", f"App {index}")
            self.assertEqual(status_code, 200)
            request_ids.append(body["request_id"])
        return request_ids

    async def test_a_fourth_request_is_refused_with_429(self) -> None:
        await self._fill()

        self._forget_daily_limit()
        status_code, body = await self._request("com.app9", "App 9")
        self.assertEqual(status_code, 429)
        self.assertEqual(
            body, {"error": "A parent is already looking at your requests."}
        )
        self.assertEqual(
            len(PENDING.unblock_requests(self.hass)),
            PROVISION.MAX_PENDING_UNBLOCK_REQUESTS,
        )

    async def test_a_duplicate_is_still_a_409_not_a_429(self) -> None:
        await self._fill()

        status_code, body = await self._request("com.app0", "App 0")
        self.assertEqual(status_code, 409)
        self.assertEqual(
            body, {"error": "A parent already has a request for that app."}
        )

    async def test_the_cap_frees_up_when_a_request_is_answered(self) -> None:
        request_ids = await self._fill()
        self.assertEqual((await self._request("com.app9", "App 9"))[0], 429)

        await PENDING.async_approve_unblock_request(self.hass, request_ids[0])
        self._forget_daily_limit()
        self.assertEqual((await self._request("com.app9", "App 9"))[0], 200)

        await PENDING.async_reject_unblock_request(self.hass, request_ids[1])
        self._forget_daily_limit()
        self.assertEqual((await self._request("com.app8", "App 8"))[0], 200)

    async def test_the_cap_frees_up_when_a_request_expires(self) -> None:
        request_ids = await self._fill()
        self._forget_daily_limit()
        self.assertEqual((await self._request("com.app9", "App 9"))[0], 429)

        store = PENDING.unblock_store(self.hass)
        for request_id in request_ids:
            store.get(request_id, ha_stub.utcnow()).requested_at = (
                ha_stub.utcnow() - EXPIRED
            )
        self.assertEqual((await self._request("com.app9", "App 9"))[0], 200)

    async def test_a_second_app_the_same_day_is_refused_with_429(self) -> None:
        status_code, _body = await self._request()
        self.assertEqual(status_code, 200)

        status_code, body = await self._request("com.minecraft", "Minecraft")
        self.assertEqual(status_code, 429)
        self.assertEqual(
            body,
            {
                "error": (
                    "Your child has already asked for an app today. "
                    "They can ask again tomorrow."
                )
            },
        )
        # The first request still stands, and the second was never recorded.
        self.assertEqual(
            [request.package for request in PENDING.unblock_requests(self.hass)],
            ["com.roblox.client"],
        )
        self.assertEqual(self.runtime.unblock_request_day, date.today().isoformat())

    async def test_a_duplicate_on_the_same_day_is_a_409_not_the_daily_429(self) -> None:
        await self._request()
        status_code, body = await self._request()
        self.assertEqual(status_code, 409)
        self.assertEqual(
            body, {"error": "A parent already has a request for that app."}
        )

    async def test_the_day_boundary_allows_the_next_request(self) -> None:
        await self._request()
        self.assertFalse(self.runtime.unblock_request_allowed_today)

        self.runtime.unblock_request_day = (
            date.today() - timedelta(days=1)
        ).isoformat()
        self.assertTrue(self.runtime.unblock_request_allowed_today)

        status_code, _body = await self._request("com.minecraft", "Minecraft")
        self.assertEqual(status_code, 200)
        self.assertEqual(
            [request.package for request in PENDING.unblock_requests(self.hass)],
            ["com.roblox.client", "com.minecraft"],
        )

    async def test_the_daily_limit_survives_a_restart(self) -> None:
        await self.runtime.async_load()
        await self._request()

        restarted = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id="casey-phone",
            device_name="Casey's phone",
            windows_username="Casey",
            api_key="existing-key",
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        await restarted.async_load()
        self.assertEqual(restarted.unblock_request_day, date.today().isoformat())
        self.assertFalse(restarted.unblock_request_allowed_today)

    async def test_the_payload_carries_the_daily_gate(self) -> None:
        response = await self._state()
        self.assertTrue(response.data["unblock_request_allowed_today"])
        self.assertIsNone(response.data["unblock_request_next_at"])

        await self._request()
        response = await self._state()
        self.assertFalse(response.data["unblock_request_allowed_today"])
        next_at = datetime.fromisoformat(response.data["unblock_request_next_at"])
        self.assertEqual((next_at.hour, next_at.minute, next_at.second), (0, 0, 0))
        self.assertGreater(next_at, ha_stub.utcnow())

    async def _state(self):
        request = FakeRequest(
            self.hass, headers={"X-Device-Blocker-Key": "existing-key"}
        )
        return await API.PcStateView().get(request, "casey-phone")

    async def test_the_payload_carries_the_count_and_the_cap(self) -> None:
        response = await self._state()
        self.assertEqual(response.data["unblock_requests_pending"], 0)
        self.assertEqual(
            response.data["unblock_requests_limit"],
            PROVISION.MAX_PENDING_UNBLOCK_REQUESTS,
        )

        await self._request()
        response = await self._state()
        self.assertEqual(response.data["unblock_requests_pending"], 1)

    async def test_requests_survive_a_home_assistant_restart(self) -> None:
        _status, body = await self._request()
        restarted = FakeHass()
        restarted.storage = dict(self.hass.storage)
        await PENDING.async_load_pending(restarted)
        self.assertEqual(
            [request.request_id for request in PENDING.unblock_requests(restarted)],
            [body["request_id"]],
        )


class UnblockEntityTests(unittest.IsolatedAsyncioTestCase):
    """The sensor and buttons a parent answers the request with."""

    def setUp(self) -> None:
        self.hass = FakeHass()
        self.runtime = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id="casey-phone",
            device_name="Casey's phone",
            windows_username="Casey",
            api_key="existing-key",
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        self.hass.data.setdefault(CONST.DOMAIN, {})["entry-1"] = self.runtime

    async def _request(self, package: str = "com.roblox.client", label: str = "Roblox"):
        return await PENDING.async_create_unblock_request(
            self.hass, self.runtime, package, label
        )

    def _sensor(self):
        sensor = SENSOR.UnblockRequestsSensor(self.runtime)
        sensor.hass = self.hass
        return sensor

    async def test_the_sensor_state_names_the_apps_that_were_asked_for(self) -> None:
        sensor = self._sensor()
        self.assertEqual(sensor.native_value, "none")
        self.assertEqual(
            sensor.extra_state_attributes,
            {
                "requests": [],
                "pending": 0,
                "limit": PROVISION.MAX_PENDING_UNBLOCK_REQUESTS,
                "allowed_today": True,
                "next_allowed_at": None,
                "requested_today": False,
            },
        )

        _status, body = await self._request()
        request = PENDING.unblock_requests(self.hass)[0]
        self.assertEqual(sensor.native_value, "Roblox")
        attributes = sensor.extra_state_attributes
        self.assertEqual(attributes["pending"], 1)
        self.assertEqual(attributes["limit"], PROVISION.MAX_PENDING_UNBLOCK_REQUESTS)
        self.assertEqual(
            attributes["requests"],
            [
                {
                    "package": "com.roblox.client",
                    "label": "Roblox",
                    "requested_at": request.requested_at.isoformat(),
                }
            ],
        )
        self.assertFalse(attributes["allowed_today"])
        self.assertTrue(attributes["requested_today"])
        self.assertIsNotNone(attributes["next_allowed_at"])
        self.assertEqual(sensor.unique_id, "casey-phone_unblock_requests")
        self.assertEqual(object_id(sensor), "caseys_phone_unblock_requests")

        # An answered request is no longer waiting on a parent, but the daily
        # gate stays shut until tomorrow.
        await PENDING.async_reject_unblock_request(self.hass, body["request_id"])
        self.assertEqual(sensor.native_value, "none")
        self.assertEqual(sensor.extra_state_attributes["pending"], 0)
        self.assertFalse(sensor.extra_state_attributes["allowed_today"])

    async def test_the_sensor_names_every_app_in_the_order_they_were_asked(self) -> None:
        sensor = self._sensor()
        await self._request()
        # The second ask is a new day, so the daily gate is open again.
        self.runtime.unblock_request_day = "2000-01-01"
        await self._request("com.minecraft", "Minecraft")
        self.assertEqual(sensor.native_value, "Roblox, Minecraft")
        self.assertEqual(
            [request["label"] for request in sensor.extra_state_attributes["requests"]],
            ["Roblox", "Minecraft"],
        )

    async def test_the_sensor_writes_its_state_when_a_request_arrives(self) -> None:
        sensor = self._sensor()
        await sensor.async_added_to_hass()
        await self._request()
        # Once for the runtime change (the daily gate closed) and once for the
        # pending store, which is what adds the request.
        self.assertEqual(sensor.state_writes, 2)

    async def test_the_buttons_appear_for_a_request_and_go_when_it_is_approved(self) -> None:
        added: list = []
        manager = BUTTON.PendingRequestButtons(self.hass, added.extend)
        self.assertEqual(added, [])

        _status, body = await self._request()
        self.assertEqual(
            sorted(object_id(button) for button in added),
            [
                f"rowe_pc_blocker_approve_unblock_{body['request_id']}",
                f"rowe_pc_blocker_reject_unblock_{body['request_id']}",
            ],
        )
        approve = next(button for button in added if button._action == BUTTON.APPROVE)
        self.assertEqual(approve.extra_state_attributes["package"], "com.roblox.client")
        self.assertEqual(approve.extra_state_attributes["device_id"], "casey-phone")
        for button in added:
            button.entity_id = f"button.{object_id(button)}"

        await approve.async_press()
        await self.hass.async_drain()

        self.assertTrue(all(button.removed for button in added))
        self.assertEqual(
            self.hass.entity_registry.removed,
            [f"button.{object_id(button)}" for button in added],
        )
        self.assertEqual(PENDING.unblock_requests(self.hass), [])
        self.assertIn(
            "com.roblox.client", APPS.parse_allowed_apps(self.runtime.allowed_apps)
        )
        manager.async_stop()

    async def test_rejecting_removes_the_buttons_without_allowing_the_app(self) -> None:
        added: list = []
        manager = BUTTON.PendingRequestButtons(self.hass, added.extend)
        _status, _body = await self._request()
        reject = next(button for button in added if button._action == BUTTON.REJECT)
        for button in added:
            button.entity_id = f"button.{object_id(button)}"

        await reject.async_press()
        await self.hass.async_drain()

        self.assertTrue(all(button.removed for button in added))
        self.assertEqual(self.runtime.allowed_apps, "")
        manager.async_stop()

    async def test_an_expired_request_loses_its_buttons(self) -> None:
        added: list = []
        manager = BUTTON.PendingRequestButtons(self.hass, added.extend)
        _status, _body = await self._request()
        sweep, interval = self.hass.intervals[0]
        self.assertEqual(interval, timedelta(seconds=60))

        PENDING.unblock_requests(self.hass)[0].requested_at = ha_stub.utcnow() - EXPIRED
        sweep(None)
        await self.hass.async_drain()

        self.assertTrue(all(button.removed for button in added))
        manager.async_stop()
        self.assertEqual(self.hass.intervals, [])
        self.assertEqual(self.hass.dispatcher[CONST.SIGNAL_PENDING], [])

    async def test_a_setup_request_and_an_unblock_request_each_get_their_buttons(self) -> None:
        added: list = []
        manager = BUTTON.PendingRequestButtons(self.hass, added.extend)
        setup = await PENDING.async_create_request(self.hass, "Casey")
        _status, body = await self._request()
        self.assertEqual(
            sorted(object_id(button) for button in added),
            sorted(
                [
                    f"rowe_pc_blocker_approve_{setup.request_id}",
                    f"rowe_pc_blocker_reject_{setup.request_id}",
                    f"rowe_pc_blocker_approve_unblock_{body['request_id']}",
                    f"rowe_pc_blocker_reject_unblock_{body['request_id']}",
                ]
            ),
        )

        # Answering one kind leaves the other kind's buttons alone.
        await PENDING.async_reject_unblock_request(self.hass, body["request_id"])
        await self.hass.async_drain()
        self.assertEqual(
            sorted(
                object_id(button) for button in added if not button.removed
            ),
            [
                f"rowe_pc_blocker_approve_{setup.request_id}",
                f"rowe_pc_blocker_reject_{setup.request_id}",
            ],
        )
        manager.async_stop()

    async def test_a_phone_gets_the_sensor_and_a_pc_does_not(self) -> None:
        phone: list = []
        await SENSOR.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id="entry-1"), phone.extend
        )
        self.assertTrue(
            any(isinstance(entity, SENSOR.UnblockRequestsSensor) for entity in phone)
        )

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
        pc: list = []
        await SENSOR.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id="entry-2"), pc.extend
        )
        self.assertFalse(
            any(isinstance(entity, SENSOR.UnblockRequestsSensor) for entity in pc)
        )


if __name__ == "__main__":
    unittest.main()
