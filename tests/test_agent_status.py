from datetime import datetime, timedelta, timezone
import importlib.util
from pathlib import Path
import sys
import unittest


MODULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "custom_components"
    / "rowe_pc_blocker"
    / "agent_status.py"
)
SPEC = importlib.util.spec_from_file_location("rowe_pc_blocker_agent_status", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
STATUS = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = STATUS
SPEC.loader.exec_module(STATUS)


class AgentStatusTests(unittest.TestCase):
    def test_missing_ui_status_keeps_the_previous_value(self) -> None:
        self.assertEqual(
            STATUS.resolve_ui_status("portal_ready", {"application": "Idle"}),
            "portal_ready",
        )
        self.assertEqual(STATUS.resolve_ui_error("still there", {}), "still there")

    def test_explicit_idle_replaces_a_sticky_starting_status(self) -> None:
        self.assertEqual(
            STATUS.resolve_ui_status("starting", {"ui_status": "idle", "ui_error": ""}),
            "idle",
        )
        self.assertEqual(
            STATUS.resolve_ui_error("old", {"ui_status": "idle", "ui_error": ""}),
            "",
        )

    def test_idle_and_disabled_are_not_portal_errors(self) -> None:
        self.assertFalse(STATUS.is_portal_error_status("idle"))
        self.assertFalse(STATUS.is_portal_error_status("disabled"))
        self.assertFalse(STATUS.is_portal_error_status("portal_idle"))
        self.assertFalse(STATUS.is_portal_error_status("portal_ready"))
        self.assertTrue(STATUS.is_portal_error_status("portal_navigation_failed"))

    def test_fail_open_alerts_only_while_a_block_is_requested(self) -> None:
        now = datetime(2026, 10, 7, tzinfo=timezone.utc)
        self.assertFalse(
            STATUS.should_notify_enforcement_degraded(
                "monitoring",
                "fail_open",
                block_requested=False,
                previous_block_requested=False,
                last_notified_at=None,
                now=now,
            )
        )
        self.assertTrue(
            STATUS.should_notify_enforcement_degraded(
                "enforcing",
                "fail_open",
                block_requested=True,
                previous_block_requested=False,
                last_notified_at=None,
                now=now,
            )
        )
        self.assertTrue(
            STATUS.should_notify_enforcement_degraded(
                "fail_open",
                "fail_open",
                block_requested=True,
                previous_block_requested=False,
                last_notified_at=None,
                now=now,
            )
        )
        self.assertFalse(
            STATUS.should_notify_enforcement_degraded(
                "fail_open",
                "fail_open",
                block_requested=True,
                previous_block_requested=True,
                last_notified_at=None,
                now=now,
            )
        )

    def test_accessibility_off_alerts_even_when_the_phone_is_allowed(self) -> None:
        now = datetime(2026, 10, 7, tzinfo=timezone.utc)
        self.assertTrue(
            STATUS.should_notify_enforcement_degraded(
                "monitoring",
                "accessibility_disabled",
                block_requested=False,
                previous_block_requested=False,
                last_notified_at=None,
                now=now,
            )
        )
        self.assertFalse(
            STATUS.should_notify_enforcement_degraded(
                "accessibility_disabled",
                "accessibility_disabled",
                block_requested=False,
                previous_block_requested=False,
                last_notified_at=now - timedelta(minutes=5),
                now=now,
            )
        )


class AgentOnlineTests(unittest.TestCase):
    def test_a_device_that_never_checked_in_is_offline(self) -> None:
        now = datetime(2026, 10, 7, tzinfo=timezone.utc)
        self.assertFalse(STATUS.agent_is_online(None, now, 180))

    def test_the_timeout_is_the_boundary(self) -> None:
        now = datetime(2026, 10, 7, tzinfo=timezone.utc)
        self.assertTrue(STATUS.agent_is_online(now - timedelta(seconds=179), now, 180))
        self.assertFalse(STATUS.agent_is_online(now - timedelta(seconds=180), now, 180))


class AgentStaleTransitionTests(unittest.TestCase):
    """One event per offline episode, and one when the agent comes back."""

    def test_a_device_that_never_checked_in_is_not_stale(self) -> None:
        self.assertEqual(
            STATUS.agent_stale_transition(False, False, False), (False, None)
        )

    def test_going_silent_fires_once(self) -> None:
        self.assertEqual(
            STATUS.agent_stale_transition(False, False, True),
            (True, STATUS.AGENT_STALE),
        )
        # Still silent: no repeat, however many checks run.
        self.assertEqual(STATUS.agent_stale_transition(True, False, True), (True, None))
        self.assertEqual(STATUS.agent_stale_transition(True, False, True), (True, None))

    def test_coming_back_fires_once(self) -> None:
        self.assertEqual(
            STATUS.agent_stale_transition(True, True, True),
            (False, STATUS.AGENT_RECOVERED),
        )
        self.assertEqual(STATUS.agent_stale_transition(False, True, True), (False, None))

    def test_a_second_episode_fires_again(self) -> None:
        stale, event = STATUS.agent_stale_transition(False, False, True)
        self.assertEqual(event, STATUS.AGENT_STALE)
        stale, event = STATUS.agent_stale_transition(stale, True, True)
        self.assertEqual(event, STATUS.AGENT_RECOVERED)
        stale, event = STATUS.agent_stale_transition(stale, False, True)
        self.assertEqual((stale, event), (True, STATUS.AGENT_STALE))

    def test_an_agent_that_has_never_checked_in_never_recovers(self) -> None:
        self.assertEqual(STATUS.agent_stale_transition(False, True, False), (False, None))


if __name__ == "__main__":
    unittest.main()
