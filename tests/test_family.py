"""Which other device a child's agent is told about.

``family.py`` has no Home Assistant imports, but it does import its siblings,
so it is loaded through the package helper in ``ha_stub.py``.
"""

from __future__ import annotations

import unittest
from datetime import datetime, timedelta, timezone

import ha_stub

FAMILY = ha_stub.load("family")
PROTOCOL = ha_stub.load("protocol_surface")

NOW = datetime(2026, 10, 17, 12, 0, tzinfo=timezone.utc)
TIMEOUT = 180


class FakeRuntime:
    """The fields a peer payload reads."""

    def __init__(
        self,
        device_id,
        device_type,
        windows_username,
        *,
        blocked=False,
        message="Paused",
        last_seen=None,
        active_application="",
        challenge_payload=None,
    ) -> None:
        self.device_id = device_id
        self.device_type = device_type
        self.windows_username = windows_username
        self.effective_blocked = blocked
        self.message = message
        self.last_seen = last_seen
        self.active_application = active_application
        # The real runtime exposes this as a property built from the stored
        # challenge; the payload itself is what a peer reads.
        self.challenge_payload = challenge_payload


class FamilyPeerTests(unittest.TestCase):
    def test_a_childs_phone_and_pc_are_reported_to_each_other(self):
        pc = FakeRuntime("casey-pc", "windows", "Casey", blocked=True)
        phone = FakeRuntime(
            "casey-phone",
            "android",
            "Casey",
            last_seen=NOW - timedelta(seconds=30),
            active_application="com.whatsapp",
        )
        peers = FAMILY.family_peers(phone, [pc, phone], NOW, TIMEOUT)
        self.assertEqual(peers["pc"]["device_id"], "casey-pc")
        self.assertEqual(peers["pc"]["blocked"], True)
        self.assertEqual(peers["pc"]["agent_online"], False)
        self.assertIsNone(peers["pc"]["last_seen"])
        self.assertIsNone(peers["phone"])

        peers = FAMILY.family_peers(pc, [pc, phone], NOW, TIMEOUT)
        self.assertEqual(peers["phone"]["device_id"], "casey-phone")
        self.assertEqual(peers["phone"]["agent_online"], True)
        self.assertEqual(peers["phone"]["active_application"], "com.whatsapp")
        self.assertEqual(
            peers["phone"]["last_seen"], (NOW - timedelta(seconds=30)).isoformat()
        )
        self.assertIsNone(peers["pc"])

    def test_another_childs_devices_are_never_reported(self):
        phone = FakeRuntime("casey-phone", "android", "Casey")
        other = FakeRuntime("alex-pc", "windows", "Alex")
        peers = FAMILY.family_peers(phone, [phone, other], NOW, TIMEOUT)
        self.assertEqual(peers, {"phone": None, "pc": None})

    def test_a_child_with_two_phones_reports_the_first_by_device_id(self):
        pc = FakeRuntime("casey-pc", "windows", "Casey")
        second = FakeRuntime("casey-phone-b", "android", "Casey")
        first = FakeRuntime("casey-phone-a", "android", "Casey")
        peers = FAMILY.family_peers(pc, [pc, second, first], NOW, TIMEOUT)
        self.assertEqual(peers["phone"]["device_id"], "casey-phone-a")

    def test_a_device_without_a_child_name_has_no_peers(self):
        device = FakeRuntime("casey-phone", "android", "")
        peers = FAMILY.family_peers(device, [device], NOW, TIMEOUT)
        self.assertEqual(peers, {"phone": None, "pc": None})

    def test_the_peer_payload_is_the_documented_shape(self):
        runtime = FakeRuntime("casey-pc", "windows", "Casey")
        payload = FAMILY.peer_payload(runtime, NOW, TIMEOUT)
        self.assertEqual(set(payload), PROTOCOL.FAMILY_PEER_KEYS)

    def test_a_phone_waiting_on_a_chore_reports_its_challenge(self):
        challenge = {
            "id": "a" * 32,
            "phrase": "I have brushed my teeth",
            "sent_at": NOW.isoformat(),
        }
        pc = FakeRuntime("casey-pc", "windows", "Casey")
        phone = FakeRuntime(
            "casey-phone", "android", "Casey", challenge_payload=challenge
        )
        peers = FAMILY.family_peers(pc, [pc, phone], NOW, TIMEOUT)
        self.assertEqual(peers["phone"]["challenge"], challenge)

    def test_a_phone_with_no_challenge_reports_null(self):
        pc = FakeRuntime("casey-pc", "windows", "Casey")
        phone = FakeRuntime("casey-phone", "android", "Casey")
        peers = FAMILY.family_peers(pc, [pc, phone], NOW, TIMEOUT)
        self.assertIsNone(peers["phone"]["challenge"])

    def test_a_pc_peer_always_reports_a_null_challenge(self):
        # Challenges are phone-only, so the key is present but empty on the PC
        # peer even when the phone is waiting on one.
        phone = FakeRuntime(
            "casey-phone",
            "android",
            "Casey",
            challenge_payload={"id": "b" * 32, "phrase": "Tidy your room", "sent_at": None},
        )
        pc = FakeRuntime("casey-pc", "windows", "Casey")
        peers = FAMILY.family_peers(phone, [pc, phone], NOW, TIMEOUT)
        self.assertIsNone(peers["pc"]["challenge"])
        self.assertIn("challenge", peers["pc"])


if __name__ == "__main__":
    unittest.main()
