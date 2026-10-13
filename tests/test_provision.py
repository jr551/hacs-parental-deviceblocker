import importlib.util
import sys
import unittest
from pathlib import Path


def _load(name, filename):
    path = Path(__file__).resolve().parents[1] / "custom_components" / "rowe_pc_blocker" / filename
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


PORTAL = _load("rowe_pc_blocker_portal_for_provision", "portal.py")
PROVISION = _load("rowe_pc_blocker_provision", "provision.py")
ChildIdentity = PORTAL.ChildIdentity
PhoneEntry = PROVISION.PhoneEntry
decide_provision = PROVISION.decide_provision
phone_device_id = PROVISION.phone_device_id


CASEY = ChildIdentity(child_id="child-casey", name="Casey")
ALEX = ChildIdentity(child_id="child-alex", name="Alex")


def _phone(device_id, username, api_key="existing-key", pin=""):
    return PhoneEntry(
        device_id=device_id,
        device_name=f"{username}'s phone",
        device_type="android",
        windows_username=username,
        api_key=api_key,
        parent_pin=pin,
    )


class ProvisionTests(unittest.TestCase):
    def test_device_id_uses_the_child_name(self):
        self.assertEqual(phone_device_id("Casey"), "casey-phone")
        self.assertEqual(phone_device_id("  Alex "), "alex-phone")
        self.assertEqual(phone_device_id("Mary Ann"), "maryann-phone")
        self.assertEqual(phone_device_id("!!!"), "")

    def test_unknown_name_does_not_mint_a_key(self):
        decision = decide_provision("Sam", [CASEY, ALEX], [], "")
        self.assertEqual(decision.action, "unknown_child")
        self.assertEqual(decision.api_key, "")

    def test_existing_phone_is_reused_without_rotating_the_key(self):
        entry = _phone("casey-phone", "Casey", api_key="keep-this-key")
        decision = decide_provision("casey", [CASEY, ALEX], [entry], "")
        self.assertEqual(decision.action, "reuse")
        self.assertEqual(decision.device_id, "casey-phone")
        self.assertEqual(decision.api_key, "keep-this-key")
        self.assertEqual(decision.child_name, "Casey")

    def test_parent_pin_is_required_when_the_entry_has_one(self):
        entry = _phone("casey-phone", "Casey", pin="1234")
        missing = decide_provision("Casey", [CASEY], [entry], "")
        self.assertEqual(missing.action, "pin_required")
        self.assertEqual(missing.api_key, "")
        wrong = decide_provision("Casey", [CASEY], [entry], "0000")
        self.assertEqual(wrong.action, "bad_pin")
        self.assertEqual(wrong.api_key, "")
        ok = decide_provision("Casey", [CASEY], [entry], "1234")
        self.assertEqual(ok.action, "reuse")
        self.assertEqual(ok.api_key, "existing-key")

    def test_first_provision_creates_a_phone_for_a_known_child(self):
        decision = decide_provision("Alex", [CASEY, ALEX], [], "")
        self.assertEqual(decision.action, "create")
        self.assertEqual(decision.device_id, "alex-phone")
        self.assertEqual(decision.child_name, "Alex")
        self.assertEqual(decision.api_key, "")

    def test_android_entry_for_the_same_child_is_reused(self):
        entry = _phone("casey-handset", "Casey", api_key="keep-this-key")
        decision = decide_provision("Casey", [CASEY], [entry], "")
        self.assertEqual(decision.action, "reuse")
        self.assertEqual(decision.device_id, "casey-handset")
        self.assertEqual(decision.api_key, "keep-this-key")


if __name__ == "__main__":
    unittest.main()
