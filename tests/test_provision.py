"""The phone setup contract: what a phone is told, and when.

The rules live in ``provision.py`` as pure functions, so the whole
request/approve/collect contract can be pinned without Home Assistant.
"""

import importlib.util
import sys
import unittest
from collections import namedtuple
from datetime import datetime, timedelta, timezone
from pathlib import Path


def _load(name, filename):
    path = Path(__file__).resolve().parents[1] / "custom_components" / "rowe_pc_blocker" / filename
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


PROVISION = _load("rowe_pc_blocker_provision", "provision.py")

NOW = datetime(2026, 10, 17, 12, 0, tzinfo=timezone.utc)

# The chore-manager child a typed name is matched against.
Child = namedtuple("Child", "name")


def _phone(device_id, username, api_key="existing-key", device_type="android"):
    return PROVISION.PhoneEntry(
        device_id=device_id,
        device_name=f"{username}'s phone",
        device_type=device_type,
        windows_username=username,
        api_key=api_key,
    )


def _request(child_name="Casey", status=PROVISION.STATUS_PENDING, **kwargs):
    return PROVISION.PendingRequest(
        request_id=PROVISION.new_request_id(),
        child_name=child_name,
        requested_at=NOW,
        status=status,
        **kwargs,
    )


class DeviceIdTests(unittest.TestCase):
    def test_device_id_uses_the_child_name(self):
        self.assertEqual(PROVISION.phone_device_id("Casey"), "casey-phone")
        self.assertEqual(PROVISION.phone_device_id("  Alex "), "alex-phone")
        self.assertEqual(PROVISION.phone_device_id("Mary Ann"), "maryann-phone")
        self.assertEqual(PROVISION.phone_device_id("!!!"), "")


class MatchChildTests(unittest.TestCase):
    def test_an_unknown_name_matches_nothing(self):
        self.assertIsNone(PROVISION.match_child("Sam", [Child("Casey"), Child("Alex")]))
        self.assertIsNone(PROVISION.match_child("", [Child("Casey")]))

    def test_matching_ignores_case_and_punctuation(self):
        children = [Child("Casey"), Child("Alex")]
        self.assertEqual(PROVISION.match_child("  cAsEy ", children).name, "Casey")

    def test_an_ambiguous_name_matches_nothing(self):
        self.assertIsNone(
            PROVISION.match_child("Casey", [Child("Casey"), Child("casey")])
        )


class DecideApprovalTests(unittest.TestCase):
    def test_a_new_child_creates_a_phone(self):
        decision = PROVISION.decide_approval("Alex", [])
        self.assertEqual(decision.action, "create")
        self.assertEqual(decision.device_id, "alex-phone")
        self.assertEqual(decision.child_name, "Alex")
        self.assertEqual(decision.api_key, "")

    def test_the_same_device_id_is_reused_without_rotating_the_key(self):
        entry = _phone("casey-phone", "Casey", api_key="keep-this-key")
        decision = PROVISION.decide_approval("casey", [entry])
        self.assertEqual(decision.action, "reuse")
        self.assertEqual(decision.device_id, "casey-phone")
        self.assertEqual(decision.api_key, "keep-this-key")
        self.assertEqual(decision.child_name, "casey")

    def test_an_android_entry_for_the_same_child_is_reused(self):
        entry = _phone("casey-handset", "Casey", api_key="keep-this-key")
        decision = PROVISION.decide_approval("Casey", [entry])
        self.assertEqual(decision.action, "reuse")
        self.assertEqual(decision.device_id, "casey-handset")
        self.assertEqual(decision.api_key, "keep-this-key")

    def test_a_pc_holding_the_device_id_is_not_reused(self):
        entry = _phone("casey-phone", "Casey", device_type="windows")
        decision = PROVISION.decide_approval("Casey", [entry])
        self.assertEqual(decision.action, "unavailable")
        self.assertEqual(decision.api_key, "")

    def test_two_phones_for_one_child_are_not_guessed_between(self):
        entries = [_phone("casey-a", "Casey"), _phone("casey-b", "Casey")]
        decision = PROVISION.decide_approval("Casey", entries)
        self.assertEqual(decision.action, "unavailable")
        self.assertEqual(decision.api_key, "")

    def test_another_childs_phone_is_never_reused(self):
        decision = PROVISION.decide_approval("Casey", [_phone("alex-phone", "Alex")])
        self.assertEqual(decision.action, "create")
        self.assertEqual(decision.device_id, "casey-phone")


class ResponseTests(unittest.TestCase):
    def test_an_unknown_child_is_a_404(self):
        status_code, body = PROVISION.unknown_child_response()
        self.assertEqual(status_code, 404)
        self.assertEqual(body, {"error": "That name is not a child in Home Assistant."})

    def test_a_fresh_request_is_pending(self):
        request = _request()
        status_code, body = PROVISION.request_response(request)
        self.assertEqual(status_code, 202)
        self.assertEqual(
            body,
            {
                "status": "pending",
                "request_id": request.request_id,
                "child_name": "Casey",
            },
        )

    def test_an_already_approved_request_hands_over_the_credentials(self):
        request = _request(
            status=PROVISION.STATUS_APPROVED,
            device_id="casey-phone",
            api_key="the-key",
        )
        status_code, body = PROVISION.request_response(request)
        self.assertEqual(status_code, 200)
        self.assertEqual(
            body,
            {
                "status": "approved",
                "device_id": "casey-phone",
                "api_key": "the-key",
                "child_name": "Casey",
            },
        )

    def test_collecting_an_approved_request_returns_the_credentials(self):
        request = _request(
            status=PROVISION.STATUS_APPROVED,
            device_id="casey-phone",
            api_key="the-key",
        )
        status_code, body = PROVISION.collection_response(request)
        self.assertEqual(status_code, 200)
        self.assertEqual(body["status"], "approved")
        self.assertEqual(body["device_id"], "casey-phone")
        self.assertEqual(body["api_key"], "the-key")
        self.assertEqual(body["child_name"], "Casey")

    def test_collecting_a_rejected_request_is_a_403(self):
        status_code, body = PROVISION.collection_response(
            _request(status=PROVISION.STATUS_REJECTED)
        )
        self.assertEqual(status_code, 403)
        self.assertEqual(body["status"], "rejected")
        self.assertEqual(body["error"], "A parent declined this setup.")

    def test_collecting_an_expired_request_is_a_404(self):
        status_code, body = PROVISION.collection_response(None)
        self.assertEqual(status_code, 404)
        self.assertEqual(body, {"error": "That setup request has expired."})

    def test_collecting_a_pending_request_says_so(self):
        status_code, body = PROVISION.collection_response(_request())
        self.assertEqual(status_code, 200)
        self.assertEqual(body, {"status": "pending"})


class PendingStoreTests(unittest.TestCase):
    def setUp(self):
        self.store = PROVISION.PendingProvisionStore()

    def test_a_request_expires_after_thirty_minutes(self):
        request = self.store.create("Casey", NOW)
        just_inside = NOW + timedelta(seconds=PROVISION.PENDING_TTL_SECONDS - 1)
        self.assertIsNotNone(self.store.get(request.request_id, just_inside))
        expired = NOW + timedelta(seconds=PROVISION.PENDING_TTL_SECONDS)
        self.assertIsNone(self.store.get(request.request_id, expired))
        self.assertEqual(self.store.list(expired), [])

    def test_approving_records_the_credentials_the_phone_collects(self):
        request = self.store.create("Casey", NOW)
        approved = self.store.approve(request.request_id, NOW, "casey-phone", "the-key")
        self.assertEqual(approved.status, PROVISION.STATUS_APPROVED)
        collected = self.store.get(request.request_id, NOW)
        status_code, body = PROVISION.collection_response(collected)
        self.assertEqual(status_code, 200)
        self.assertEqual(body["api_key"], "the-key")

    def test_approving_twice_keeps_the_first_credentials(self):
        request = self.store.create("Casey", NOW)
        self.store.approve(request.request_id, NOW, "casey-phone", "first-key")
        self.assertIsNone(
            self.store.approve(request.request_id, NOW, "casey-phone", "second-key")
        )
        self.assertEqual(
            self.store.get(request.request_id, NOW).api_key, "first-key"
        )

    def test_a_rejected_request_cannot_be_approved(self):
        request = self.store.create("Casey", NOW)
        self.assertIsNotNone(self.store.reject(request.request_id, NOW))
        self.assertIsNone(
            self.store.approve(request.request_id, NOW, "casey-phone", "the-key")
        )
        self.assertIsNone(self.store.reject(request.request_id, NOW))

    def test_a_second_request_for_the_same_child_reuses_the_pending_one(self):
        first = self.store.create("Casey", NOW)
        self.assertEqual(
            self.store.pending_for_child("casey", NOW).request_id, first.request_id
        )

    def test_an_approved_request_is_handed_out_until_it_expires(self):
        request = self.store.create("Casey", NOW)
        self.store.approve(request.request_id, NOW, "casey-phone", "the-key")
        self.assertEqual(
            self.store.approved_for_child("Casey", NOW).request_id, request.request_id
        )
        expired = NOW + timedelta(seconds=PROVISION.PENDING_TTL_SECONDS)
        self.assertIsNone(self.store.approved_for_child("Casey", expired))

    def test_requests_survive_a_save_and_load(self):
        request = self.store.create("Casey", NOW)
        self.store.approve(request.request_id, NOW, "casey-phone", "the-key")
        reloaded = PROVISION.PendingProvisionStore()
        reloaded.load(self.store.dump())
        restored = reloaded.get(request.request_id, NOW)
        self.assertEqual(restored.child_name, "Casey")
        self.assertEqual(restored.status, PROVISION.STATUS_APPROVED)
        self.assertEqual(restored.device_id, "casey-phone")
        self.assertEqual(restored.api_key, "the-key")
        self.assertEqual(restored.requested_at, NOW)

    def test_an_unusable_record_is_dropped_instead_of_breaking_the_store(self):
        reloaded = PROVISION.PendingProvisionStore()
        reloaded.load(
            {
                "requests": [
                    {"request_id": "abc", "child_name": "Casey"},
                    "not a record",
                    {"request_id": "", "child_name": "Casey", "requested_at": NOW.isoformat()},
                ]
            }
        )
        self.assertEqual(reloaded.list(NOW), [])


if __name__ == "__main__":
    unittest.main()
