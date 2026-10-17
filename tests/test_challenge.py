"""The chore challenge: a phrase and a photo the child must supply to unlock.

The HTTP views are thin wrappers around the rules in ``challenge.py`` and
``evidence.py`` (the routes themselves are pinned in ``test_protocol.py``), so
these tests drive the rules, the stored photo, the services, and the entities.
"""

from __future__ import annotations

import os
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path

import ha_stub
from ha_stub import FakeConfigEntry, FakeHass, object_id

CONST = ha_stub.load("const")
CHALLENGE = ha_stub.load("challenge")
EVIDENCE = ha_stub.load("evidence")
IMAGE = ha_stub.load("image")
RUNTIME = ha_stub.load("runtime")
SENSOR = ha_stub.load("sensor")
PROTOCOL = ha_stub.load("protocol_surface")

PHONE = "casey-phone"
CHALLENGE_ENTITY = "sensor.caseys_phone_challenge"
JPEG = b"\xff\xd8\xff\xe0" + b"photo" * 10


def _backdate(path: Path, seconds: int) -> None:
    """Age a stored photo, as time passing would."""
    old = ha_stub.utcnow().timestamp() - seconds
    os.utime(path, (old, old))


class ChallengeTestCase(unittest.IsolatedAsyncioTestCase):
    """A phone with a scratch config directory to store evidence in."""

    def setUp(self) -> None:
        self.config_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.config_dir.cleanup)
        self.hass = FakeHass(config_dir=self.config_dir.name)
        self.runtime = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id=PHONE,
            device_name="Casey's phone",
            windows_username="Casey",
            api_key="existing-key",
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        self.hass.data.setdefault(CONST.DOMAIN, {})["entry-1"] = self.runtime
        self.hass.entity_registry.add(CHALLENGE_ENTITY, f"{PHONE}_challenge")

    def _attach(self, entity):
        entity.hass = self.hass
        return entity

    async def _send(self, phrase: str = "I will tidy my room") -> None:
        await self.runtime.async_send_challenge(phrase)

    async def _upload(self, data: bytes = JPEG):
        return await EVIDENCE.async_save_evidence(
            self.hass, PHONE, self.runtime.challenge_id, data
        )

    def _evidence_file(self) -> Path:
        return EVIDENCE.evidence_path(
            self.hass, PHONE, self.runtime.challenge_id
        )


class ChallengeRuntimeTests(ChallengeTestCase):
    async def test_a_new_challenge_is_pending_with_a_fresh_id(self) -> None:
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_NONE)
        self.assertIsNone(self.runtime.challenge_payload)

        await self._send()
        first = self.runtime.challenge_id
        self.assertEqual(len(first), 32)
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)
        self.assertEqual(self.runtime.challenge_phrase, "I will tidy my room")
        self.assertIsNotNone(self.runtime.challenge_sent_at)
        self.assertIsNone(self.runtime.challenge_accepted_at)

        # A second phrase replaces the first, with a new id.
        await self._send("And my homework")
        self.assertNotEqual(self.runtime.challenge_id, first)
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)
        self.assertEqual(self.runtime.challenge_phrase, "And my homework")

    async def test_the_payload_is_the_documented_shape(self) -> None:
        await self._send()
        payload = self.runtime.challenge_payload
        self.assertEqual(set(payload), PROTOCOL.CHALLENGE_PAYLOAD_KEYS)
        self.assertEqual(payload["id"], self.runtime.challenge_id)
        self.assertEqual(payload["phrase"], "I will tidy my room")
        self.assertEqual(
            payload["sent_at"], self.runtime.challenge_sent_at.isoformat()
        )

    async def test_accepting_the_current_id_flips_to_accepted(self) -> None:
        await self._send()
        accepted = await self.runtime.async_accept_challenge(self.runtime.challenge_id)
        self.assertTrue(accepted)
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_ACCEPTED)
        self.assertIsNotNone(self.runtime.challenge_accepted_at)

    async def test_a_wrong_or_missing_id_is_refused(self) -> None:
        self.assertFalse(await self.runtime.async_accept_challenge("a" * 32))
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_NONE)

        await self._send()
        self.assertFalse(await self.runtime.async_accept_challenge("a" * 32))
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)
        self.assertIsNone(self.runtime.challenge_accepted_at)

    async def test_accepting_twice_keeps_the_first_time(self) -> None:
        await self._send()
        await self.runtime.async_accept_challenge(self.runtime.challenge_id)
        first = self.runtime.challenge_accepted_at
        self.assertTrue(
            await self.runtime.async_accept_challenge(self.runtime.challenge_id)
        )
        self.assertEqual(self.runtime.challenge_accepted_at, first)

    async def test_clearing_returns_to_none(self) -> None:
        await self._send()
        await self.runtime.async_accept_challenge(self.runtime.challenge_id)
        await self.runtime.async_clear_challenge()
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_NONE)
        self.assertEqual(self.runtime.challenge_id, "")
        self.assertEqual(self.runtime.challenge_phrase, "")
        self.assertIsNone(self.runtime.challenge_sent_at)
        self.assertIsNone(self.runtime.challenge_accepted_at)
        self.assertIsNone(self.runtime.challenge_payload)

    async def test_the_challenge_survives_a_restart(self) -> None:
        await self.runtime.async_load()
        await self._send()
        await self.runtime.async_accept_challenge(self.runtime.challenge_id)

        restarted = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id=PHONE,
            device_name="Casey's phone",
            windows_username="Casey",
            api_key="existing-key",
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        await restarted.async_load()
        self.assertEqual(restarted.challenge_id, self.runtime.challenge_id)
        self.assertEqual(restarted.challenge_phrase, "I will tidy my room")
        self.assertEqual(restarted.challenge_state, RUNTIME.CHALLENGE_ACCEPTED)
        self.assertEqual(
            restarted.challenge_accepted_at, self.runtime.challenge_accepted_at
        )


class EvidenceTests(ChallengeTestCase):
    def test_a_non_jpeg_is_refused(self) -> None:
        for content_type in ("", "text/plain", "image/png", "application/json"):
            refusal = EVIDENCE.evidence_refusal(content_type, 10)
            self.assertEqual(refusal[0], 415, content_type)
            self.assertIn("error", refusal[1])
        self.assertIsNone(EVIDENCE.evidence_refusal("image/jpeg", 10))
        self.assertIsNone(EVIDENCE.evidence_refusal("image/jpeg; charset=binary", 10))

    def test_an_oversize_upload_is_refused(self) -> None:
        refusal = EVIDENCE.evidence_refusal(
            "image/jpeg", EVIDENCE.MAX_EVIDENCE_BYTES + 1
        )
        self.assertEqual(refusal[0], 413)
        self.assertIsNone(
            EVIDENCE.evidence_refusal("image/jpeg", EVIDENCE.MAX_EVIDENCE_BYTES)
        )
        # A missing Content-Length is not a refusal; the body is checked instead.
        self.assertIsNone(EVIDENCE.evidence_refusal("image/jpeg", None))

    async def test_an_upload_is_stored_until_its_window_closes(self) -> None:
        await self._send()
        expires_at = await self._upload()
        path = self._evidence_file()
        self.assertTrue(path.is_file())
        self.assertEqual(path.read_bytes(), JPEG)
        self.assertEqual(
            expires_at,
            EVIDENCE.evidence_expires_at(path),
        )
        self.assertAlmostEqual(
            expires_at,
            ha_stub.utcnow() + timedelta(seconds=EVIDENCE.EVIDENCE_TTL_SECONDS),
            delta=timedelta(seconds=5),
        )

    async def test_evidence_with_no_challenge_is_not_found(self) -> None:
        self.assertIsNone(EVIDENCE.evidence_file(self.hass, PHONE, ""))
        self.assertIsNone(EVIDENCE.evidence_file(self.hass, PHONE, "a" * 32))

    async def test_pruning_removes_an_expired_photo(self) -> None:
        await self._send()
        await self._upload()
        path = self._evidence_file()
        _backdate(path, EVIDENCE.EVIDENCE_TTL_SECONDS + 60)

        removed = await EVIDENCE.async_prune_evidence(self.hass)
        self.assertEqual(removed, 1)
        self.assertFalse(path.exists())
        self.assertFalse(path.parent.exists())

    async def test_a_fresh_photo_survives_pruning(self) -> None:
        await self._send()
        await self._upload()
        path = self._evidence_file()

        self.assertEqual(await EVIDENCE.async_prune_evidence(self.hass), 0)
        self.assertTrue(path.is_file())

    async def test_an_upload_prunes_the_previous_challenge_photo(self) -> None:
        await self._send()
        await self._upload()
        stale = self._evidence_file()
        _backdate(stale, EVIDENCE.EVIDENCE_TTL_SECONDS + 60)

        await self._send("A second chore")
        await self._upload()
        fresh = self._evidence_file()

        self.assertFalse(stale.exists())
        self.assertTrue(fresh.is_file())

    async def test_pruning_an_empty_directory_is_harmless(self) -> None:
        self.assertEqual(await EVIDENCE.async_prune_evidence(self.hass), 0)

    async def test_deleting_removes_the_photo_and_its_directory(self) -> None:
        await self._send()
        await self._upload()
        path = self._evidence_file()
        await EVIDENCE.async_delete_evidence(self.hass, PHONE, self.runtime.challenge_id)
        self.assertFalse(path.exists())
        self.assertFalse(path.parent.exists())
        # Deleting again is not an error.
        await EVIDENCE.async_delete_evidence(self.hass, PHONE, self.runtime.challenge_id)


class AcceptRuleTests(ChallengeTestCase):
    async def test_accepting_without_a_challenge_is_a_404(self) -> None:
        refusal = CHALLENGE.challenge_accept_refusal(self.hass, self.runtime, "a" * 32)
        self.assertEqual(refusal[0], 404)
        self.assertIn("error", refusal[1])

    async def test_accepting_a_stale_id_is_a_404(self) -> None:
        await self._send()
        refusal = CHALLENGE.challenge_accept_refusal(self.hass, self.runtime, "a" * 32)
        self.assertEqual(refusal[0], 404)
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)

    async def test_accepting_without_evidence_is_a_409(self) -> None:
        await self._send()
        refusal = CHALLENGE.challenge_accept_refusal(
            self.hass, self.runtime, self.runtime.challenge_id
        )
        self.assertEqual(refusal[0], 409)
        self.assertEqual(refusal[1], {"error": "Take the photo first."})
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)

    async def test_accepting_with_evidence_is_allowed(self) -> None:
        await self._send()
        await self._upload()
        self.assertIsNone(
            CHALLENGE.challenge_accept_refusal(
                self.hass, self.runtime, self.runtime.challenge_id
            )
        )
        self.assertTrue(
            await self.runtime.async_accept_challenge(self.runtime.challenge_id)
        )
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_ACCEPTED)

    async def test_evidence_for_another_challenge_does_not_count(self) -> None:
        await self._send()
        await self._upload()
        await self._send("A different chore")
        refusal = CHALLENGE.challenge_accept_refusal(
            self.hass, self.runtime, self.runtime.challenge_id
        )
        self.assertEqual(refusal[0], 409)


class ChallengeServiceTests(ChallengeTestCase):
    def setUp(self) -> None:
        super().setUp()
        CHALLENGE.async_register_services(self.hass, None, None)

    async def _call(self, service: str, data: dict) -> None:
        await self.hass.services.async_call(
            CONST.DOMAIN,
            service,
            data,
            blocking=True,
            target={"entity_id": CHALLENGE_ENTITY},
        )

    def test_the_services_are_registered_once(self) -> None:
        self.assertIn((CONST.DOMAIN, CHALLENGE.SERVICE_SEND), self.hass.services.registered)
        self.assertIn(
            (CONST.DOMAIN, CHALLENGE.SERVICE_CLEAR), self.hass.services.registered
        )
        CHALLENGE.async_register_services(self.hass, None, None)
        self.assertEqual(len(self.hass.services.registered), 2)

    async def test_sending_from_the_dashboard_starts_a_challenge(self) -> None:
        await self._call(CHALLENGE.SERVICE_SEND, {"phrase": "  I will tidy my room  "})
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)
        self.assertEqual(self.runtime.challenge_phrase, "I will tidy my room")

    async def test_an_empty_or_oversize_phrase_is_refused(self) -> None:
        for phrase in ("", "   ", "x" * (CHALLENGE.MAX_CHALLENGE_PHRASE_LENGTH + 1)):
            with self.assertRaises(ha_stub.ServiceValidationError):
                await self._call(CHALLENGE.SERVICE_SEND, {"phrase": phrase})
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_NONE)

    async def test_a_phrase_at_the_limit_is_accepted(self) -> None:
        phrase = "x" * CHALLENGE.MAX_CHALLENGE_PHRASE_LENGTH
        await self._call(CHALLENGE.SERVICE_SEND, {"phrase": phrase})
        self.assertEqual(self.runtime.challenge_phrase, phrase)

    async def test_targeting_something_that_is_not_a_challenge_sensor_fails(self) -> None:
        with self.assertRaises(ha_stub.ServiceValidationError):
            await self.hass.services.async_call(
                CONST.DOMAIN,
                CHALLENGE.SERVICE_SEND,
                {"phrase": "Tidy up"},
                blocking=True,
                target={"entity_id": "sensor.caseys_phone_installed_apps"},
            )

    async def test_clearing_removes_the_challenge_and_its_photo(self) -> None:
        await self._call(CHALLENGE.SERVICE_SEND, {"phrase": "Tidy up"})
        await self._upload()
        path = self._evidence_file()
        self.assertTrue(path.is_file())

        await self._call(CHALLENGE.SERVICE_CLEAR, {})
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_NONE)
        self.assertFalse(path.exists())


class ChallengeEntityTests(ChallengeTestCase):
    async def test_the_sensor_reports_the_state_and_the_evidence(self) -> None:
        sensor = self._attach(SENSOR.ChallengeSensor(self.runtime))
        self.assertEqual(sensor.native_value, "none")
        self.assertEqual(sensor.unique_id, "casey-phone_challenge")
        self.assertEqual(object_id(sensor), "caseys_phone_challenge")
        self.assertEqual(
            sensor.extra_state_attributes,
            {
                "phrase": None,
                "sent_at": None,
                "accepted_at": None,
                "evidence_at": None,
                "evidence_expires_at": None,
            },
        )

        await self._send()
        self.assertEqual(sensor.native_value, "pending")
        self.assertEqual(sensor.extra_state_attributes["phrase"], "I will tidy my room")
        self.assertEqual(
            sensor.extra_state_attributes["sent_at"],
            self.runtime.challenge_sent_at.isoformat(),
        )
        self.assertIsNone(sensor.extra_state_attributes["evidence_at"])

        await self._upload()
        path = self._evidence_file()
        attributes = sensor.extra_state_attributes
        self.assertEqual(
            attributes["evidence_at"],
            ha_stub.utc_from_timestamp(path.stat().st_mtime).isoformat(),
        )
        self.assertEqual(
            attributes["evidence_expires_at"],
            EVIDENCE.evidence_expires_at(path).isoformat(),
        )

        await self.runtime.async_accept_challenge(self.runtime.challenge_id)
        self.assertEqual(sensor.native_value, "accepted")
        self.assertEqual(
            sensor.extra_state_attributes["accepted_at"],
            self.runtime.challenge_accepted_at.isoformat(),
        )

    async def test_the_image_serves_the_stored_photo(self) -> None:
        entity = IMAGE.ChallengeEvidenceImage(self.runtime)
        self.assertEqual(entity.unique_id, "casey-phone_challenge_evidence")
        self.assertEqual(object_id(entity), "caseys_phone_challenge_evidence")
        self.assertIsNone(entity.image_last_updated)
        self.assertIsNone(await entity.async_image())

        await self._send()
        await self._upload()
        path = self._evidence_file()
        self.assertEqual(
            entity.image_last_updated,
            ha_stub.utc_from_timestamp(path.stat().st_mtime),
        )
        self.assertEqual(await entity.async_image(), JPEG)

        # A cleared challenge takes the photo with it.
        await self.runtime.async_clear_challenge()
        self.assertIsNone(entity.image_last_updated)
        self.assertIsNone(await entity.async_image())

    async def test_a_phone_gets_the_challenge_entities_and_a_pc_does_not(self) -> None:
        sensors: list = []
        await SENSOR.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id="entry-1"), sensors.extend
        )
        self.assertTrue(
            any(isinstance(entity, SENSOR.ChallengeSensor) for entity in sensors)
        )

        images: list = []
        await IMAGE.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id="entry-1"), images.extend
        )
        self.assertEqual(len(images), 1)

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
        pc_sensors: list = []
        await SENSOR.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id="entry-2"), pc_sensors.extend
        )
        self.assertFalse(
            any(isinstance(entity, SENSOR.ChallengeSensor) for entity in pc_sensors)
        )
        pc_images: list = []
        await IMAGE.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id="entry-2"), pc_images.extend
        )
        self.assertEqual(pc_images, [])


if __name__ == "__main__":
    unittest.main()
