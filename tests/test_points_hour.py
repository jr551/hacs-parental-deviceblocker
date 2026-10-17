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
SPEC = importlib.util.spec_from_file_location("rowe_pc_blocker_portal_points", PORTAL_PATH)
assert SPEC is not None and SPEC.loader is not None
PORTAL = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PORTAL
SPEC.loader.exec_module(PORTAL)
points_hour_refusal = PORTAL.points_hour_refusal
POINTS_HOUR_COST = 200


class PointsHourTests(unittest.TestCase):
    def test_a_blocked_phone_with_enough_points_can_buy_an_hour(self):
        self.assertIsNone(points_hour_refusal(POINTS_HOUR_COST, True))
        self.assertIsNone(points_hour_refusal(1035, True))

    def test_refuses_when_unblocked_unmapped_or_short(self):
        self.assertEqual(points_hour_refusal(500, False), "not_blocked")
        self.assertEqual(points_hour_refusal(None, True), "no_points")
        self.assertEqual(points_hour_refusal(199, True), "insufficient")


if __name__ == "__main__":
    unittest.main()
