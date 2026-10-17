import importlib.util
import sys
import unittest
from pathlib import Path


WAKE_PATH = (
    Path(__file__).resolve().parents[1]
    / "custom_components"
    / "rowe_pc_blocker"
    / "wake_alarm.py"
)
SPEC = importlib.util.spec_from_file_location("rowe_pc_blocker_wake_alarm", WAKE_PATH)
assert SPEC is not None and SPEC.loader is not None
WAKE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = WAKE
SPEC.loader.exec_module(WAKE)
normalize_wake_alarm_form = WAKE.normalize_wake_alarm_form
wake_alarm_payload = WAKE.wake_alarm_payload


class WakeAlarmTests(unittest.TestCase):
    def test_missing_options_stay_off_on_weekday_mornings(self) -> None:
        payload = wake_alarm_payload({})
        self.assertEqual(
            payload,
            {"enabled": False, "hour": 7, "minute": 0, "days": 0b0011111, "volume": 100},
        )

    def test_each_device_options_dict_is_independent(self) -> None:
        first = wake_alarm_payload(
            {
                "wake_alarm_enabled": True,
                "wake_alarm_time": "06:40",
                "wake_alarm_days": ["mon", "wed", "fri"],
                "wake_alarm_volume": 40,
            }
        )
        second = wake_alarm_payload(
            {
                "wake_alarm_enabled": True,
                "wake_alarm_time": "07:15:00",
                "wake_alarm_days": ["sat", "sun"],
                "wake_alarm_volume": 90,
            }
        )
        self.assertEqual(first["hour"], 6)
        self.assertEqual(first["minute"], 40)
        self.assertEqual(first["days"], 0b0010101)
        self.assertEqual(first["volume"], 40)
        self.assertEqual(second["hour"], 7)
        self.assertEqual(second["minute"], 15)
        self.assertEqual(second["days"], 0b1100000)
        self.assertEqual(second["volume"], 90)
        self.assertNotEqual(first, second)

    def test_form_rejects_an_enabled_alarm_with_no_days_or_bad_volume(self) -> None:
        _cleaned, errors = normalize_wake_alarm_form(
            {
                "wake_alarm_enabled": True,
                "wake_alarm_time": "25:00",
                "wake_alarm_days": [],
                "wake_alarm_volume": 120,
            }
        )
        self.assertEqual(errors["wake_alarm_time"], "invalid_wake_time")
        self.assertEqual(errors["wake_alarm_days"], "wake_days_required")
        self.assertEqual(errors["wake_alarm_volume"], "invalid_wake_volume")

    def test_form_accepts_silent_volume_and_normalizes_seconds(self) -> None:
        cleaned, errors = normalize_wake_alarm_form(
            {
                "wake_alarm_enabled": True,
                "wake_alarm_time": "06:05:00",
                "wake_alarm_days": ["sun", "mon"],
                "wake_alarm_volume": 0,
            }
        )
        self.assertEqual(errors, {})
        self.assertEqual(cleaned["wake_alarm_time"], "06:05")
        self.assertEqual(cleaned["wake_alarm_days"], ["mon", "sun"])
        self.assertEqual(cleaned["wake_alarm_volume"], 0)
        self.assertTrue(cleaned["wake_alarm_enabled"])


if __name__ == "__main__":
    unittest.main()
