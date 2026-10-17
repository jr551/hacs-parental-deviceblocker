"""On-demand location: the request, the answer, and the tracker.

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
LOCATION = ha_stub.load("location")
PROTOCOL = ha_stub.load("protocol_surface")
RUNTIME = ha_stub.load("runtime")
TRACKER = ha_stub.load("device_tracker")

DEVICE_KEY = "the-device-key"
PHONE = "casey-phone"


def _answer(
    request_id: str,
    latitude=51.37,
    longitude=-0.28,
    accuracy=12.5,
    provider="fused",
    fix_age_seconds=3,
) -> dict:
    return {
        "request_id": request_id,
        "latitude": latitude,
        "longitude": longitude,
        "accuracy": accuracy,
        "provider": provider,
        "fix_age_seconds": fix_age_seconds,
    }


class LocationTestCase(unittest.IsolatedAsyncioTestCase):
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
        self.location_view = API.PcLocationView()
        self.state_view = API.PcStateView()

    def _request(self, payload=None, *, raw: bytes | None = None) -> FakeRequest:
        body = raw if raw is not None else json.dumps(payload or {}).encode()
        return FakeRequest(
            self.hass, body=body, headers={"X-Device-Blocker-Key": DEVICE_KEY}
        )

    async def _answer(self, payload, *, raw: bytes | None = None):
        return await self.location_view.post(self._request(payload, raw=raw), PHONE)

    async def _state(self):
        return await self.state_view.get(self._request(), PHONE)

    def _expire(self) -> None:
        self.runtime.location_requested_at = ha_stub.utcnow() - timedelta(
            seconds=LOCATION.LOCATION_REQUEST_TTL_SECONDS + 1
        )


class LocationRequestTests(LocationTestCase):
    async def test_a_request_is_created_and_carried_on_the_state_payload(self) -> None:
        self.assertIsNone(self.runtime.location_request_payload)
        self.assertIsNone((await self._state()).data["location_request"])

        request_id = await self.runtime.async_request_location()
        self.assertEqual(len(request_id), 32)
        self.assertEqual(request_id, request_id.lower())

        payload = self.runtime.location_request_payload
        self.assertEqual(set(payload), PROTOCOL.LOCATION_REQUEST_PAYLOAD_KEYS)
        self.assertEqual(payload["id"], request_id)
        self.assertEqual(
            payload["requested_at"], self.runtime.location_requested_at.isoformat()
        )
        self.assertEqual((await self._state()).data["location_request"], payload)

    async def test_a_second_press_replaces_the_request(self) -> None:
        first = await self.runtime.async_request_location()
        second = await self.runtime.async_request_location()
        self.assertNotEqual(first, second)
        self.assertEqual(self.runtime.location_request_payload["id"], second)

    async def test_the_button_asks_for_a_fix(self) -> None:
        button = BUTTON.RequestLocationButton(self.runtime)
        self.assertEqual(object_id(button), "caseys_phone_request_location")
        await button.async_press()
        self.assertTrue(self.runtime.location_request_id)

    async def test_an_expired_request_is_dropped(self) -> None:
        await self.runtime.async_request_location()
        self._expire()

        # It stops riding the payload at once, and the next poll clears it.
        self.assertIsNone(self.runtime.location_request_payload)
        self.assertTrue(await self.runtime.async_expire_location_request())
        self.assertEqual(self.runtime.location_request_id, "")
        self.assertIsNone((await self._state()).data["location_request"])

        # A live request is left alone.
        await self.runtime.async_request_location()
        self.assertFalse(await self.runtime.async_expire_location_request())
        self.assertTrue(self.runtime.location_request_id)

    async def test_the_pending_request_survives_a_restart(self) -> None:
        request_id = await self.runtime.async_request_location()
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
        self.assertEqual(restarted.location_request_id, request_id)
        self.assertEqual(restarted.location_request_payload["id"], request_id)


class LocationAnswerTests(LocationTestCase):
    async def test_a_valid_answer_stores_the_fix_and_clears_the_request(self) -> None:
        request_id = await self.runtime.async_request_location()
        response = await self._answer(_answer(request_id))
        self.assertEqual(response.status, 200)
        self.assertEqual(response.data, PROTOCOL.OK_BODY)

        self.assertEqual(self.runtime.latitude, 51.37)
        self.assertEqual(self.runtime.longitude, -0.28)
        self.assertEqual(self.runtime.location_accuracy, 12.5)
        self.assertEqual(self.runtime.location_provider, "fused")
        self.assertEqual(self.runtime.location_fix_age_seconds, 3)
        self.assertTrue(self.runtime.location_reported_at)
        self.assertEqual(self.runtime.location_request_id, "")
        self.assertIsNone(self.runtime.location_request_payload)

    async def test_a_stale_request_id_is_refused_with_404(self) -> None:
        await self.runtime.async_request_location()
        response = await self._answer(_answer("b" * 32))
        self.assertEqual(response.status, 404)
        self.assertIn("error", response.data)
        self.assertIsNone(self.runtime.latitude)

    async def test_an_answer_after_expiry_is_refused_with_404(self) -> None:
        request_id = await self.runtime.async_request_location()
        self._expire()
        response = await self._answer(_answer(request_id))
        self.assertEqual(response.status, 404)
        self.assertIsNone(self.runtime.latitude)

    async def test_a_late_answer_cannot_overwrite_a_newer_request(self) -> None:
        first = await self.runtime.async_request_location()
        second = await self.runtime.async_request_location()
        response = await self._answer(_answer(first))
        self.assertEqual(response.status, 404)
        self.assertIsNone(self.runtime.latitude)
        # The newer request is still the one the phone should answer.
        self.assertEqual(self.runtime.location_request_id, second)

    async def test_out_of_range_coordinates_are_refused_with_400(self) -> None:
        request_id = await self.runtime.async_request_location()
        for payload in (
            _answer(request_id, latitude=91.0),
            _answer(request_id, latitude=-91.0),
            _answer(request_id, longitude=-181.0),
            _answer(request_id, latitude="north"),
            {"request_id": request_id},
        ):
            response = await self._answer(payload)
            self.assertEqual(response.status, 400, payload)
            self.assertIn("error", response.data)

        self.assertIsNone(self.runtime.latitude)
        # The request is still pending, so the phone can try again.
        self.assertEqual(self.runtime.location_request_id, request_id)

    async def test_a_body_that_is_not_a_json_object_is_refused_with_400(self) -> None:
        await self.runtime.async_request_location()
        response = await self._answer(None, raw=b'"nope"')
        self.assertEqual(response.status, 400)
        self.assertIn("error", response.data)

    async def test_a_nonsense_fix_age_is_dropped_rather_than_shown(self) -> None:
        request_id = await self.runtime.async_request_location()
        await self._answer(_answer(request_id, fix_age_seconds=-5))
        self.assertIsNone(self.runtime.location_fix_age_seconds)
        self.assertEqual(self.runtime.latitude, 51.37)


class LocationTrackerTests(LocationTestCase):
    async def test_the_tracker_is_unknown_until_the_first_fix(self) -> None:
        tracker = TRACKER.LocationTracker(self.runtime)
        self.assertEqual(object_id(tracker), "caseys_phone_location")
        self.assertIsNone(tracker.latitude)
        self.assertIsNone(tracker.longitude)
        self.assertEqual(tracker.location_accuracy, 0)
        self.assertEqual(
            tracker.extra_state_attributes,
            {
                "location_age_seconds": None,
                "location_provider": None,
                "reported_at": None,
                "requested_at": None,
            },
        )

    async def test_the_tracker_reports_the_fix_and_its_age(self) -> None:
        request_id = await self.runtime.async_request_location()
        await self._answer(_answer(request_id, fix_age_seconds=30))

        tracker = TRACKER.LocationTracker(self.runtime)
        self.assertEqual(tracker.latitude, 51.37)
        self.assertEqual(tracker.longitude, -0.28)
        self.assertEqual(tracker.location_accuracy, 12)
        attributes = tracker.extra_state_attributes
        self.assertEqual(attributes["location_provider"], "fused")
        self.assertEqual(attributes["location_age_seconds"], 30)
        self.assertEqual(
            attributes["reported_at"], self.runtime.location_reported_at.isoformat()
        )
        self.assertEqual(
            attributes["requested_at"], self.runtime.location_requested_at.isoformat()
        )

        # The age keeps growing, so a fix nobody has refreshed cannot read as
        # current.
        self.runtime.location_reported_at = ha_stub.utcnow() - timedelta(minutes=5)
        self.assertEqual(tracker.extra_state_attributes["location_age_seconds"], 330)

    async def test_the_tracker_keeps_the_last_fix_while_the_agent_is_silent(self) -> None:
        request_id = await self.runtime.async_request_location()
        await self._answer(_answer(request_id))
        tracker = TRACKER.LocationTracker(self.runtime)

        self.runtime.last_seen = ha_stub.utcnow() - timedelta(
            seconds=CONST.ONLINE_TIMEOUT_SECONDS + 1
        )
        self.assertTrue(tracker.available)
        self.assertEqual(tracker.latitude, 51.37)


class LocationRuleTests(unittest.TestCase):
    def test_a_request_is_only_live_for_its_ttl(self) -> None:
        now = ha_stub.utcnow()
        self.assertFalse(LOCATION.request_is_live(None, now))
        self.assertTrue(LOCATION.request_is_live(now, now))
        self.assertTrue(
            LOCATION.request_is_live(
                now - timedelta(seconds=LOCATION.LOCATION_REQUEST_TTL_SECONDS - 1), now
            )
        )
        self.assertFalse(
            LOCATION.request_is_live(
                now - timedelta(seconds=LOCATION.LOCATION_REQUEST_TTL_SECONDS), now
            )
        )


if __name__ == "__main__":
    unittest.main()
