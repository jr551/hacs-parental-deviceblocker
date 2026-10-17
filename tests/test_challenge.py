"""The chore challenge: a phrase and a photo a parent approves.

The HTTP views are thin wrappers around the rules in ``challenge.py`` and
``evidence.py`` (the routes themselves are pinned in ``test_protocol.py``), so
these tests drive the rules, the stored photo, the services, and the entities.
"""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path

import ha_stub
from ha_stub import FakeConfigEntry, FakeHass, FakeRequest, object_id

API = ha_stub.load("api")
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
        self.assertIsNone(self.runtime.challenge_submitted_at)
        self.assertIsNone(self.runtime.challenge_approved_at)

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
        self.assertEqual(payload["status"], RUNTIME.CHALLENGE_PENDING)
        self.assertIsNone(payload["submitted_at"])

    async def test_submitting_the_current_id_flips_to_submitted(self) -> None:
        await self._send()
        submitted = await self.runtime.async_submit_challenge(
            self.runtime.challenge_id
        )
        self.assertTrue(submitted)
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_SUBMITTED)
        self.assertIsNotNone(self.runtime.challenge_submitted_at)
        self.assertIsNone(self.runtime.challenge_approved_at)

        # The phone is still locked: the payload says it is waiting on a parent.
        payload = self.runtime.challenge_payload
        self.assertEqual(payload["status"], RUNTIME.CHALLENGE_SUBMITTED)
        self.assertEqual(
            payload["submitted_at"], self.runtime.challenge_submitted_at.isoformat()
        )

    async def test_a_wrong_or_missing_id_is_refused(self) -> None:
        self.assertFalse(await self.runtime.async_submit_challenge("a" * 32))
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_NONE)

        await self._send()
        self.assertFalse(await self.runtime.async_submit_challenge("a" * 32))
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)
        self.assertIsNone(self.runtime.challenge_submitted_at)

    async def test_submitting_twice_keeps_the_first_time(self) -> None:
        await self._send()
        await self.runtime.async_submit_challenge(self.runtime.challenge_id)
        first = self.runtime.challenge_submitted_at
        self.assertTrue(
            await self.runtime.async_submit_challenge(self.runtime.challenge_id)
        )
        self.assertEqual(self.runtime.challenge_submitted_at, first)

    async def test_approving_clears_the_payload_so_the_phone_unlocks(self) -> None:
        await self._send()
        await self.runtime.async_submit_challenge(self.runtime.challenge_id)

        self.assertTrue(await self.runtime.async_approve_challenge())
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_APPROVED)
        self.assertIsNotNone(self.runtime.challenge_approved_at)
        # That null is how the phone knows the chore is done.
        self.assertIsNone(self.runtime.challenge_payload)

        # Approving again is a no-op, and a late submit is refused.
        self.assertTrue(await self.runtime.async_approve_challenge())
        self.assertFalse(
            await self.runtime.async_submit_challenge(self.runtime.challenge_id)
        )

    async def test_approving_nothing_is_refused(self) -> None:
        self.assertFalse(await self.runtime.async_approve_challenge())

        await self._send()
        # Still pending: the child has not sent the photo yet.
        self.assertFalse(await self.runtime.async_approve_challenge())
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)

    async def test_rejecting_sends_the_challenge_back_to_pending(self) -> None:
        await self._send()
        await self.runtime.async_submit_challenge(self.runtime.challenge_id)

        self.assertTrue(await self.runtime.async_reject_challenge())
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)
        self.assertIsNone(self.runtime.challenge_submitted_at)
        self.assertIsNone(self.runtime.challenge_approved_at)
        self.assertEqual(self.runtime.challenge_payload["status"], "pending")

        # Nothing is waiting any more, so a second reject is a no-op.
        self.assertFalse(await self.runtime.async_reject_challenge())

    async def test_clearing_returns_to_none(self) -> None:
        await self._send()
        await self.runtime.async_submit_challenge(self.runtime.challenge_id)
        await self.runtime.async_approve_challenge()
        await self.runtime.async_clear_challenge()
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_NONE)
        self.assertEqual(self.runtime.challenge_id, "")
        self.assertEqual(self.runtime.challenge_phrase, "")
        self.assertIsNone(self.runtime.challenge_sent_at)
        self.assertIsNone(self.runtime.challenge_submitted_at)
        self.assertIsNone(self.runtime.challenge_approved_at)
        self.assertIsNone(self.runtime.challenge_payload)

    async def test_the_challenge_survives_a_restart(self) -> None:
        await self.runtime.async_load()
        await self._send()
        await self.runtime.async_submit_challenge(self.runtime.challenge_id)
        await self.runtime.async_approve_challenge()

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
        self.assertEqual(restarted.challenge_state, RUNTIME.CHALLENGE_APPROVED)
        self.assertEqual(
            restarted.challenge_submitted_at, self.runtime.challenge_submitted_at
        )
        self.assertEqual(
            restarted.challenge_approved_at, self.runtime.challenge_approved_at
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


class SubmitRuleTests(ChallengeTestCase):
    async def test_submitting_without_a_challenge_is_a_404(self) -> None:
        refusal = CHALLENGE.challenge_submit_refusal(self.hass, self.runtime, "a" * 32)
        self.assertEqual(refusal[0], 404)
        self.assertIn("error", refusal[1])

    async def test_submitting_a_stale_id_is_a_404(self) -> None:
        await self._send()
        refusal = CHALLENGE.challenge_submit_refusal(self.hass, self.runtime, "a" * 32)
        self.assertEqual(refusal[0], 404)
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)

    async def test_submitting_without_evidence_is_a_409(self) -> None:
        await self._send()
        refusal = CHALLENGE.challenge_submit_refusal(
            self.hass, self.runtime, self.runtime.challenge_id
        )
        self.assertEqual(refusal[0], 409)
        self.assertEqual(refusal[1], {"error": "Take the photo first."})
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)

    async def test_submitting_with_evidence_is_allowed(self) -> None:
        await self._send()
        await self._upload()
        self.assertIsNone(
            CHALLENGE.challenge_submit_refusal(
                self.hass, self.runtime, self.runtime.challenge_id
            )
        )
        self.assertTrue(
            await self.runtime.async_submit_challenge(self.runtime.challenge_id)
        )
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_SUBMITTED)

    async def test_an_approved_challenge_is_no_longer_active(self) -> None:
        await self._send()
        await self._upload()
        await self.runtime.async_submit_challenge(self.runtime.challenge_id)
        await self.runtime.async_approve_challenge()

        refusal = CHALLENGE.challenge_submit_refusal(
            self.hass, self.runtime, self.runtime.challenge_id
        )
        self.assertEqual(refusal[0], 404)

    async def test_evidence_for_another_challenge_does_not_count(self) -> None:
        await self._send()
        await self._upload()
        await self._send("A different chore")
        refusal = CHALLENGE.challenge_submit_refusal(
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
        for service in (
            CHALLENGE.SERVICE_SEND,
            CHALLENGE.SERVICE_APPROVE,
            CHALLENGE.SERVICE_REJECT,
            CHALLENGE.SERVICE_CLEAR,
        ):
            self.assertIn((CONST.DOMAIN, service), self.hass.services.registered)
        CHALLENGE.async_register_services(self.hass, None, None)
        self.assertEqual(len(self.hass.services.registered), 4)

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

    async def test_approving_from_the_dashboard_unlocks_the_phone(self) -> None:
        await self._call(CHALLENGE.SERVICE_SEND, {"phrase": "Tidy up"})
        await self._upload()
        await self.runtime.async_submit_challenge(self.runtime.challenge_id)

        await self._call(CHALLENGE.SERVICE_APPROVE, {})
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_APPROVED)
        self.assertIsNone(self.runtime.challenge_payload)
        # The photo stays until the challenge is cleared, so a parent can still
        # look at what they approved.
        self.assertTrue(self._evidence_file().is_file())

    async def test_rejecting_from_the_dashboard_deletes_the_photo(self) -> None:
        await self._call(CHALLENGE.SERVICE_SEND, {"phrase": "Tidy up"})
        await self._upload()
        path = self._evidence_file()
        await self.runtime.async_submit_challenge(self.runtime.challenge_id)

        await self._call(CHALLENGE.SERVICE_REJECT, {})
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)
        self.assertFalse(path.exists())
        # The child can do the chore again: the challenge is still active.
        self.assertEqual(self.runtime.challenge_payload["status"], "pending")

    async def test_clearing_removes_the_challenge_and_its_photo(self) -> None:
        await self._call(CHALLENGE.SERVICE_SEND, {"phrase": "Tidy up"})
        await self._upload()
        path = self._evidence_file()
        self.assertTrue(path.is_file())

        await self._call(CHALLENGE.SERVICE_CLEAR, {})
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_NONE)
        self.assertFalse(path.exists())


class ChallengeSubmitViewTests(ChallengeTestCase):
    """The endpoint a phone calls once the child has sent the photo."""

    def setUp(self) -> None:
        super().setUp()
        self.view = API.PcChallengeSubmitView()

    async def _submit(self, payload, *, raw: bytes | None = None):
        body = raw if raw is not None else json.dumps(payload).encode()
        request = FakeRequest(
            self.hass, body=body, headers={"X-Device-Blocker-Key": "existing-key"}
        )
        return await self.view.post(request, PHONE)

    async def test_a_submit_with_evidence_is_accepted(self) -> None:
        await self._send()
        await self._upload()
        response = await self._submit({"id": self.runtime.challenge_id})
        self.assertEqual(response.status, 200)
        self.assertEqual(set(response.data), PROTOCOL.CHALLENGE_SUBMIT_RESPONSE_KEYS)
        self.assertEqual(response.data["status"], "submitted")
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_SUBMITTED)
        # The phone is still locked until a parent decides.
        self.assertIsNotNone(self.runtime.challenge_payload)

    async def test_a_submit_without_evidence_is_a_409(self) -> None:
        await self._send()
        response = await self._submit({"id": self.runtime.challenge_id})
        self.assertEqual(response.status, 409)
        self.assertEqual(response.data, {"error": "Take the photo first."})
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)

    async def test_a_stale_id_is_a_404(self) -> None:
        await self._send()
        await self._upload()
        response = await self._submit({"id": "a" * 32})
        self.assertEqual(response.status, 404)
        self.assertEqual(self.runtime.challenge_state, RUNTIME.CHALLENGE_PENDING)


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
                "submitted_at": None,
                "approved_at": None,
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

        await self.runtime.async_submit_challenge(self.runtime.challenge_id)
        self.assertEqual(sensor.native_value, "submitted")
        self.assertEqual(
            sensor.extra_state_attributes["submitted_at"],
            self.runtime.challenge_submitted_at.isoformat(),
        )

        await self.runtime.async_approve_challenge()
        self.assertEqual(sensor.native_value, "approved")
        self.assertEqual(
            sensor.extra_state_attributes["approved_at"],
            self.runtime.challenge_approved_at.isoformat(),
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
