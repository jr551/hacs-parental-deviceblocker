import importlib.util
import sys
import unittest
from pathlib import Path


PORTAL_PATH = (
    Path(__file__).resolve().parents[1]
    / "custom_components"
    / "rowe_pc_blocker"
    / "portal.py"
)
SPEC = importlib.util.spec_from_file_location("rowe_pc_blocker_portal", PORTAL_PATH)
assert SPEC is not None and SPEC.loader is not None
PORTAL = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PORTAL
SPEC.loader.exec_module(PORTAL)
ChildIdentity = PORTAL.ChildIdentity
build_portal_snapshot = PORTAL.build_portal_snapshot
resolve_child_identity = PORTAL.resolve_child_identity


class PortalTests(unittest.TestCase):
    def setUp(self) -> None:
        self.points = [
            {
                "state": "0",
                "attributes": {"child_id": "child-one-id", "child_name": "Child One"},
            },
            {
                "state": "160",
                "attributes": {"child_id": "child-two-id", "child_name": "Child Two"},
            },
        ]

    def test_child_resolution_uses_installed_windows_account(self) -> None:
        self.assertEqual(
            resolve_child_identity("childone", "Child One PC", self.points),
            ChildIdentity("child-one-id", "Child One"),
        )
        self.assertEqual(
            resolve_child_identity("childtwo", "Child Two PC", self.points),
            ChildIdentity("child-two-id", "Child Two"),
        )

    def test_unique_shortened_account_name_is_supported(self) -> None:
        self.assertEqual(
            resolve_child_identity("childt", "Bedroom PC", self.points),
            ChildIdentity("child-two-id", "Child Two"),
        )

    def test_snapshot_never_contains_the_other_child_activity(self) -> None:
        child = ChildIdentity("child-two-id", "Child Two")
        entities = {
            "points": {
                "state": "160",
                "attributes": {
                    "unit_of_measurement": "Stars",
                    "total_points_earned": 200,
                },
            },
            "stats": {
                "attributes": {
                    "assigned_chores": [
                        {"id": "chore-1", "name": "Have shower", "points": 50}
                    ]
                }
            },
            "chores": {"attributes": {"todays_completions": []}},
            "availability": {
                "attributes": {
                    "chore_availability": {"chore-1": {"child-two-id": True}}
                }
            },
            "rewards": {
                "attributes": {
                    "rewards": [
                        {
                            "id": "reward-1",
                            "name": "Extra time",
                            "cost": 20,
                            "assigned_to": ["child-two-id"],
                            "is_available": True,
                        },
                        {
                            "id": "child-one-only",
                            "name": "Child One item",
                            "cost": 1,
                            "assigned_to": ["child-one-id"],
                            "is_available": True,
                        },
                    ]
                }
            },
            "activity": {
                "attributes": {
                    "recent_transactions": [
                        {
                            "child_id": "child-two-id",
                            "points": -20,
                            "reason": "Extra time",
                            "created_at": "2026-07-22T12:00:00+00:00",
                        },
                        {
                            "child_id": "child-one-id",
                            "points": -999,
                            "reason": "Must not leak",
                            "created_at": "2026-07-22T13:00:00+00:00",
                        },
                    ]
                }
            },
        }
        buttons = [
            {
                "state": "unknown",
                "attributes": {
                    "child_id": "child-two-id",
                    "chore_id": "chore-1",
                },
            },
            {
                "state": "unknown",
                "attributes": {
                    "child_id": "child-two-id",
                    "reward_id": "reward-1",
                },
            },
        ]

        snapshot = build_portal_snapshot(child, entities, buttons)

        self.assertEqual(snapshot["child"]["points"], 160)
        self.assertEqual([item["id"] for item in snapshot["rewards"]], ["reward-1"])
        self.assertTrue(snapshot["rewards"][0]["can_claim"])
        self.assertTrue(snapshot["chores"][0]["can_complete"])
        self.assertEqual([item["reason"] for item in snapshot["activity"]], ["Extra time"])

    def test_time_offers_surface_screen_time_rewards(self):
        child = ChildIdentity(child_id="kid-1", name="Child One")
        entities = {
            "points": {"state": "50", "attributes": {"unit_of_measurement": "Stars"}},
            "rewards": {"attributes": {"rewards": [
                {"id": "r-evening", "name": "PC time until 10:30pm", "cost": 20,
                 "assigned_to": ["kid-1"], "is_available": True,
                 "calculated_costs": {"kid-1": 20}},
                {"id": "r-morning", "name": "Early morning PC time", "cost": 100,
                 "assigned_to": ["kid-1"], "is_available": True,
                 "calculated_costs": {"kid-1": 100}},
                {"id": "r-voucher", "name": "£10 Roblox voucher", "cost": 10000,
                 "assigned_to": [], "is_available": True,
                 "calculated_costs": {"kid-1": 10000}},
            ]}},
        }
        buttons = [
            {"state": "unknown", "attributes": {"child_id": "kid-1", "reward_id": "r-evening"}},
            {"state": "unknown", "attributes": {"child_id": "kid-1", "reward_id": "r-morning"}},
            {"state": "unknown", "attributes": {"child_id": "kid-1", "reward_id": "r-voucher"}},
        ]

        snapshot = build_portal_snapshot(child, entities, buttons)

        offers = snapshot["time_offers"]
        self.assertEqual([o["id"] for o in offers], ["r-evening", "r-morning"])
        self.assertTrue(offers[0]["can_claim"])       # 50 points >= 20
        self.assertFalse(offers[1]["can_claim"])      # 50 points < 100
        # non-time rewards stay out of the banner but remain in the shop
        self.assertIn("r-voucher", [r["id"] for r in snapshot["rewards"]])


if __name__ == "__main__":
    unittest.main()
