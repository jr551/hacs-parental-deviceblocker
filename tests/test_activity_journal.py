from datetime import datetime, timedelta, timezone
import importlib.util
from pathlib import Path
import sys
import unittest


MODULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "custom_components"
    / "rowe_pc_blocker"
    / "activity_journal.py"
)
SPEC = importlib.util.spec_from_file_location("rowe_pc_blocker_activity_journal", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
JOURNAL = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = JOURNAL
SPEC.loader.exec_module(JOURNAL)


class ActivityJournalTests(unittest.TestCase):
    def test_structured_result_is_bounded_and_risk_level_is_derived(self) -> None:
        result = JOURNAL.parse_provider_assessment(
            '```json\n{"summary":"Ordinary Roblox gameplay",'
            '"category":"gaming","risk_score":4,'
            '"reasons":["Visible unsafe link", "Second reason"]}\n```'
        )
        self.assertEqual(result.summary, "Ordinary Roblox gameplay")
        self.assertEqual(result.category, "gaming")
        self.assertEqual(result.risk_score, 4)
        self.assertEqual(result.risk_level, "high")
        self.assertEqual(len(result.reasons), 2)

    def test_plain_text_provider_result_falls_back_without_high_alert(self) -> None:
        result = JOURNAL.parse_provider_assessment("A game is visible.")
        self.assertEqual(result.category, "unknown")
        self.assertEqual(result.risk_score, 1)
        self.assertEqual(result.risk_level, "low")

    def test_weekly_report_counts_categories_and_high_checks(self) -> None:
        now = datetime(2026, 7, 22, 18, tzinfo=timezone.utc)
        records = [
            JOURNAL.ActivityRecord(
                now - timedelta(hours=2), "Roblox gameplay.", "gaming", 0
            ),
            JOURNAL.ActivityRecord(
                now - timedelta(days=2), "A scam page is visible.", "scams_security", 4
            ),
            JOURNAL.ActivityRecord(
                now - timedelta(days=8), "Old activity.", "browsing", 0
            ),
        ]
        report = JOURNAL.build_activity_report(
            records, child_name="Child One", now=now, period="week"
        )
        self.assertEqual(report["checks"], 2)
        self.assertEqual(report["highest_risk_level"], "high")
        self.assertEqual(report["high_or_critical_checks"], 1)
        self.assertEqual(report["categories"]["gaming"], 1)
        self.assertNotIn("Old activity", report["full_summary"])

    def test_today_uses_local_calendar_day(self) -> None:
        local = timezone(timedelta(hours=1))
        now = datetime(2026, 7, 22, 0, 30, tzinfo=local)
        records = [
            JOURNAL.ActivityRecord(
                datetime(2026, 7, 21, 23, 45, tzinfo=timezone.utc),
                "Late game.",
                "gaming",
                0,
            )
        ]
        report = JOURNAL.build_activity_report(
            records, child_name="Child Two", now=now, period="today"
        )
        self.assertEqual(report["checks"], 1)


if __name__ == "__main__":
    unittest.main()
