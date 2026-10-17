"""The New challenge box on the device page."""

from __future__ import annotations

import unittest

import ha_stub
from ha_stub import FakeConfigEntry, FakeHass

CONST = ha_stub.load("const")
RUNTIME = ha_stub.load("runtime")
TEXT = ha_stub.load("text")


class NewChallengeTextTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.hass = FakeHass()

    def _runtime(self, device_type: str, device_id: str = "casey-phone"):
        runtime = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id=device_id,
            device_name=f"{device_id} device",
            windows_username="Casey",
            api_key="existing-key",
            device_type=device_type,
        )
        self.hass.data.setdefault(CONST.DOMAIN, {})["entry-1"] = runtime
        return runtime

    async def test_a_phone_offers_the_box_and_a_pc_does_not(self) -> None:
        self._runtime(CONST.DEVICE_TYPE_WINDOWS, "casey-pc")
        entry = FakeConfigEntry({}, entry_id="entry-1")

        pc_texts: list = []
        await TEXT.async_setup_entry(self.hass, entry, pc_texts.extend)
        self.assertFalse(any(isinstance(e, TEXT.NewChallengeText) for e in pc_texts))

        phone = self._runtime(CONST.DEVICE_TYPE_ANDROID)
        phone_texts: list = []
        await TEXT.async_setup_entry(self.hass, entry, phone_texts.extend)
        self.assertTrue(any(isinstance(e, TEXT.NewChallengeText) for e in phone_texts))

    async def test_typing_a_phrase_starts_the_challenge(self) -> None:
        runtime = self._runtime(CONST.DEVICE_TYPE_ANDROID)
        box = TEXT.NewChallengeText(runtime)

        self.assertEqual(box.native_value, "")
        self.assertEqual(runtime.challenge_state, "none")

        await box.async_set_value("  feed the cat  ")

        self.assertEqual(runtime.challenge_state, "pending")
        self.assertEqual(runtime.challenge_phrase, "feed the cat")
        self.assertIsNotNone(runtime.challenge_sent_at)
        # The box is a trigger, not a setting: it does not echo the phrase back.
        self.assertEqual(box.native_value, "")

    async def test_clearing_the_box_cancels_nothing(self) -> None:
        runtime = self._runtime(CONST.DEVICE_TYPE_ANDROID)
        await runtime.async_send_challenge("tidy your room")
        box = TEXT.NewChallengeText(runtime)

        await box.async_set_value("   ")

        self.assertEqual(runtime.challenge_state, "pending")
        self.assertEqual(runtime.challenge_phrase, "tidy your room")

    async def test_a_second_phrase_replaces_the_running_challenge(self) -> None:
        runtime = self._runtime(CONST.DEVICE_TYPE_ANDROID)
        box = TEXT.NewChallengeText(runtime)

        await box.async_set_value("tidy your room")
        first_id = runtime.challenge_id
        await box.async_set_value("feed the cat")

        self.assertEqual(runtime.challenge_phrase, "feed the cat")
        self.assertNotEqual(runtime.challenge_id, first_id)
        self.assertEqual(runtime.challenge_attempt, 1)
