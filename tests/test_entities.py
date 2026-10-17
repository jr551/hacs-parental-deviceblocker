"""Entity state and attributes, and the agent stale events.

These tests load the integration modules against the stand-ins in
``ha_stub.py``, so they exercise the real entity classes without a Home
Assistant install.
"""

from __future__ import annotations

import unittest
from datetime import timedelta
from types import SimpleNamespace

import ha_stub
from ha_stub import FakeConfigEntry, FakeHass, FakeState, object_id

CONST = ha_stub.load("const")
PENDING = ha_stub.load("pending")
PROVISION = ha_stub.load("provision")
RUNTIME = ha_stub.load("runtime")
SENSOR = ha_stub.load("sensor")
TEXT = ha_stub.load("text")
BUTTON = ha_stub.load("button")
BINARY_SENSOR = ha_stub.load("binary_sensor")
SWITCH = ha_stub.load("switch")
TRACKER = ha_stub.load("device_tracker")
IMAGE = ha_stub.load("image")
STATUS = ha_stub.load("agent_status")

PHONE_DATA = {
    CONST.CONF_DEVICE_ID: "casey-phone",
    CONST.CONF_DEVICE_NAME: "Casey's phone",
    CONST.CONF_DEVICE_TYPE: CONST.DEVICE_TYPE_ANDROID,
    CONST.CONF_WINDOWS_USERNAME: "Casey",
    CONST.CONF_API_KEY: "existing-key",
}


class EntityTestCase(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.hass = FakeHass()
        self.runtime = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id="casey-phone",
            device_name="Casey's phone",
            windows_username="Casey",
            api_key="existing-key",
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )

    async def _pending_request(self, child_name: str = "Casey"):
        return await PENDING.async_create_request(self.hass, child_name)

    def _attach(self, entity):
        """Give the entity the hass the platform would set before adding it."""
        entity.hass = self.hass
        return entity


class InstalledAppsSensorTests(EntityTestCase):
    async def test_counts_and_lists_the_apps_the_phone_reported(self) -> None:
        sensor = SENSOR.InstalledAppsSensor(self.runtime)
        self.assertEqual(sensor.native_value, 0)
        self.assertEqual(sensor.extra_state_attributes, {"apps": []})

        self.runtime.installed_apps = [
            {"package": "com.whatsapp", "label": "WhatsApp"},
            {"package": "com.android.dialer", "label": "Phone"},
        ]
        self.assertEqual(sensor.native_value, 2)
        self.assertEqual(
            sensor.extra_state_attributes["apps"],
            [
                {"package": "com.whatsapp", "label": "WhatsApp"},
                {"package": "com.android.dialer", "label": "Phone"},
            ],
        )
        self.assertEqual(sensor.unique_id, "casey-phone_installed_apps")


class AllowedAppsTextTests(EntityTestCase):
    async def test_a_parent_edit_is_normalised(self) -> None:
        entity = TEXT.AllowedAppsText(self.runtime)
        await entity.async_set_value(" com.whatsapp ,com.android.dialer,com.whatsapp ")
        self.assertEqual(
            self.runtime.allowed_apps, "com.whatsapp, com.android.dialer"
        )
        self.assertEqual(entity.native_value, "com.whatsapp, com.android.dialer")

    async def test_a_typo_is_refused_instead_of_dropped(self) -> None:
        entity = TEXT.AllowedAppsText(self.runtime)
        with self.assertRaises(ValueError):
            await entity.async_set_value("com.whatsapp, not a package")
        self.assertEqual(self.runtime.allowed_apps, "")

    async def test_clearing_the_field_falls_back_to_the_built_in_list(self) -> None:
        entity = TEXT.AllowedAppsText(self.runtime)
        await entity.async_set_value("com.whatsapp")
        await entity.async_set_value("")
        self.assertEqual(self.runtime.allowed_apps, "")

    async def test_the_last_value_is_restored_after_a_restart(self) -> None:
        entity = self._attach(TEXT.AllowedAppsText(self.runtime))
        entity.restored_state = FakeState("com.whatsapp, com.android.dialer")
        await entity.async_added_to_hass()
        self.assertEqual(
            self.runtime.allowed_apps, "com.whatsapp, com.android.dialer"
        )

    async def test_an_unreadable_restored_value_does_not_break_setup(self) -> None:
        entity = self._attach(TEXT.AllowedAppsText(self.runtime))
        entity.restored_state = FakeState("com.whatsapp, ???")
        await entity.async_added_to_hass()
        self.assertEqual(self.runtime.allowed_apps, "com.whatsapp")


class AgentOnlineSensorTests(EntityTestCase):
    async def test_last_seen_is_reported_and_online_follows_the_timeout(self) -> None:
        sensor = BINARY_SENSOR.PcOnlineSensor(self.runtime)
        self.assertFalse(sensor.is_on)
        self.assertEqual(
            sensor.extra_state_attributes,
            {
                "last_seen": None,
                "last_seen_age_seconds": None,
                "online_timeout_seconds": CONST.ONLINE_TIMEOUT_SECONDS,
            },
        )

        self.runtime.last_seen = ha_stub.utcnow()
        self.assertTrue(sensor.is_on)
        attributes = sensor.extra_state_attributes
        self.assertEqual(attributes["last_seen"], self.runtime.last_seen.isoformat())
        self.assertEqual(attributes["last_seen_age_seconds"], 0)

        self.runtime.last_seen = ha_stub.utcnow() - timedelta(
            seconds=CONST.ONLINE_TIMEOUT_SECONDS + 1
        )
        self.assertFalse(sensor.is_on)
        self.assertEqual(
            sensor.extra_state_attributes["last_seen_age_seconds"],
            CONST.ONLINE_TIMEOUT_SECONDS + 1,
        )

    async def test_the_tick_fires_one_stale_event_per_episode(self) -> None:
        sensor = self._attach(BINARY_SENSOR.PcOnlineSensor(self.runtime))
        await sensor.async_added_to_hass()
        self.assertEqual(len(self.hass.intervals), 1)
        tick, interval = self.hass.intervals[0]
        self.assertEqual(interval, timedelta(seconds=30))

        # A device that has never checked in is not stale: nothing to report.
        await tick(None)
        self.assertEqual(self.hass.bus.fired(CONST.EVENT_AGENT_STALE), [])

        self.runtime.last_seen = ha_stub.utcnow() - timedelta(minutes=5)
        await tick(None)
        await tick(None)
        await tick(None)
        stale = self.hass.bus.fired(CONST.EVENT_AGENT_STALE)
        self.assertEqual(len(stale), 1)
        self.assertEqual(stale[0]["device_id"], "casey-phone")
        self.assertEqual(stale[0]["minutes_offline"], 5)
        self.assertEqual(self.hass.bus.fired(CONST.EVENT_AGENT_RECOVERED), [])

        self.runtime.last_seen = ha_stub.utcnow()
        await tick(None)
        await tick(None)
        recovered = self.hass.bus.fired(CONST.EVENT_AGENT_RECOVERED)
        self.assertEqual(len(recovered), 1)
        self.assertEqual(recovered[0]["device_id"], "casey-phone")
        self.assertEqual(len(self.hass.bus.fired(CONST.EVENT_AGENT_STALE)), 1)

        # A second episode is reported again.
        self.runtime.last_seen = ha_stub.utcnow() - timedelta(minutes=9)
        await tick(None)
        self.assertEqual(len(self.hass.bus.fired(CONST.EVENT_AGENT_STALE)), 2)

    async def test_a_restart_during_an_outage_does_not_report_it_twice(self) -> None:
        await self.runtime.async_load()
        self.runtime.last_seen = ha_stub.utcnow() - timedelta(minutes=5)
        await self.runtime.check_agent_stale()
        self.assertEqual(len(self.hass.bus.fired(CONST.EVENT_AGENT_STALE)), 1)

        restarted = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id="casey-phone",
            device_name="Casey's phone",
            windows_username="Casey",
            api_key="existing-key",
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        await restarted.async_load()
        restarted.last_seen = self.runtime.last_seen
        self.assertTrue(restarted.agent_stale)
        await restarted.check_agent_stale()
        self.assertEqual(len(self.hass.bus.fired(CONST.EVENT_AGENT_STALE)), 1)

        # The agent coming back is still reported, once.
        restarted.last_seen = ha_stub.utcnow()
        await restarted.check_agent_stale()
        await restarted.check_agent_stale()
        self.assertEqual(len(self.hass.bus.fired(CONST.EVENT_AGENT_RECOVERED)), 1)
        self.assertFalse(restarted.agent_stale)


class FakeScreenMonitor:
    """The screen-monitor fields the sensors read, without aiohttp or PIL."""

    def __init__(self, configured: bool = True) -> None:
        self.settings = SimpleNamespace(configured=configured, schedule_mode="manual")
        self.status = "idle"
        self.last_summary = ""
        self.last_error = ""
        self.last_checked_at = None
        self.last_attempted_at = None
        self.last_trigger = ""
        self.last_category = "unknown"
        self.last_risk_score = 0
        self.last_risk_level = "none"
        self.last_reasons: tuple = ()
        self.next_check_at = None


class TelemetryAvailabilityTests(EntityTestCase):
    """A silent agent must not leave stale readings on the dashboard."""

    def setUp(self) -> None:
        super().setUp()
        self.runtime.last_seen = ha_stub.utcnow()

    def _go_silent(self) -> None:
        self.runtime.last_seen = ha_stub.utcnow() - timedelta(
            seconds=CONST.ONLINE_TIMEOUT_SECONDS + 1
        )

    def test_agent_telemetry_goes_unavailable_while_the_agent_is_silent(self) -> None:
        entities = [
            SENSOR.ActiveApplicationSensor(self.runtime),
            SENSOR.ActiveWindowSensor(self.runtime),
            SENSOR.EnforcementStatusSensor(self.runtime),
            SENSOR.InstalledAppsSensor(self.runtime),
        ]
        for entity in entities:
            self.assertTrue(entity.available, entity.name)
        self._go_silent()
        for entity in entities:
            self.assertFalse(entity.available, entity.name)

    def test_a_device_that_never_checked_in_is_not_presented_as_live(self) -> None:
        self.runtime.last_seen = None
        self.assertFalse(SENSOR.ActiveApplicationSensor(self.runtime).available)

    async def test_the_block_switch_stays_settable_while_the_agent_is_silent(self) -> None:
        switch = SWITCH.PcBlockedSwitch(self.runtime)
        self._go_silent()
        self.assertTrue(switch.available)
        await switch.async_turn_on()
        self.assertTrue(self.runtime.blocked)

    def test_parent_controls_and_home_assistant_state_stay_available(self) -> None:
        entities = [
            BINARY_SENSOR.PcOnlineSensor(self.runtime),
            TEXT.PcMessageText(self.runtime),
            TEXT.AllowedAppsText(self.runtime),
            SENSOR.ChallengeSensor(self.runtime),
            SENSOR.UnblockRequestsSensor(self.runtime),
            IMAGE.ChallengeEvidenceImage(self.runtime),
            # The location tracker is a stored answer, not a live feed: it
            # keeps the last fix and exposes its age instead.
            TRACKER.LocationTracker(self.runtime),
        ]
        self._go_silent()
        for entity in entities:
            self.assertTrue(entity.available, entity.name)

    async def test_the_stale_tick_rerenders_the_telemetry_entities(self) -> None:
        sensor = self._attach(SENSOR.ActiveApplicationSensor(self.runtime))
        await sensor.async_added_to_hass()
        self.assertTrue(sensor.available)
        self.assertEqual(sensor.state_writes, 0)

        self._go_silent()
        await self.runtime.check_agent_stale()
        # The runtime tells every entity to re-read itself, so the dashboard
        # shows unavailable without waiting for another agent poll.
        self.assertEqual(sensor.state_writes, 1)
        self.assertFalse(sensor.available)

        self.runtime.last_seen = ha_stub.utcnow()
        await self.runtime.check_agent_stale()
        self.assertEqual(sensor.state_writes, 2)
        self.assertTrue(sensor.available)


class BlockedSwitchTests(EntityTestCase):
    async def test_the_switch_says_why_the_block_is_not_in_force(self) -> None:
        switch = SWITCH.PcBlockedSwitch(self.runtime)
        self.assertEqual(switch.extra_state_attributes["block_reason"], "off")
        self.assertIsNone(switch.extra_state_attributes["extension_until"])

        await switch.async_turn_on()
        attributes = switch.extra_state_attributes
        self.assertFalse(attributes["effective_blocked"])
        self.assertEqual(attributes["block_reason"], "grace_period")
        self.assertEqual(
            attributes["enforce_at"], self.runtime.enforce_at.isoformat()
        )

        self.runtime.block_requested_at = ha_stub.utcnow() - timedelta(
            seconds=CONST.INITIAL_GRACE_SECONDS + 1
        )
        attributes = switch.extra_state_attributes
        self.assertTrue(attributes["effective_blocked"])
        self.assertEqual(attributes["block_reason"], "enforced")

        self.runtime.extension_until = ha_stub.utcnow() + timedelta(minutes=30)
        attributes = switch.extra_state_attributes
        self.assertFalse(attributes["effective_blocked"])
        self.assertEqual(attributes["block_reason"], "extension")
        self.assertEqual(
            attributes["extension_until"], self.runtime.extension_until.isoformat()
        )

        await switch.async_turn_off()
        self.assertEqual(switch.extra_state_attributes["block_reason"], "off")


class StatusSensorTests(EntityTestCase):
    def test_the_enforcement_sensor_flags_every_degraded_status(self) -> None:
        sensor = SENSOR.EnforcementStatusSensor(self.runtime)
        self.assertEqual(sensor.native_value, "unknown")
        self.assertFalse(sensor.extra_state_attributes["degraded"])

        for status in sorted(STATUS.DEGRADED_UI_STATUSES):
            self.runtime.ui_status = status
            self.assertTrue(sensor.extra_state_attributes["degraded"], status)

        self.runtime.ui_status = "enforcing"
        self.assertFalse(sensor.extra_state_attributes["degraded"])

    def test_the_portal_sensor_flags_a_broken_kiosk(self) -> None:
        sensor = SENSOR.PortalStatusSensor(self.runtime)
        self.assertTrue(sensor.extra_state_attributes["healthy"])

        self.runtime.ui_status = "portal_error"
        self.assertFalse(sensor.extra_state_attributes["healthy"])

        self.runtime.ui_status = "portal_ready"
        self.assertTrue(sensor.extra_state_attributes["healthy"])


class EnumSensorTests(EntityTestCase):
    def test_the_challenge_sensor_offers_every_state_it_can_report(self) -> None:
        sensor = SENSOR.ChallengeSensor(self.runtime)
        self.assertEqual(sensor.device_class, SENSOR.SensorDeviceClass.ENUM)
        self.assertEqual(set(sensor.options), set(SENSOR.CHALLENGE_STATES))

        # Every state the runtime can report must be one of the options, or
        # Home Assistant rejects the reading.
        self.assertIn(sensor.native_value, sensor.options)
        self.runtime.challenge_id = "a" * 32
        self.assertIn(sensor.native_value, sensor.options)
        self.runtime.challenge_submitted_at = ha_stub.utcnow()
        self.assertIn(sensor.native_value, sensor.options)
        self.runtime.challenge_approved_at = ha_stub.utcnow()
        self.assertIn(sensor.native_value, sensor.options)

    def test_the_screen_safety_sensor_offers_every_risk_level(self) -> None:
        self.runtime.screen_monitor = FakeScreenMonitor()
        sensor = SENSOR.ScreenSafetySensor(self.runtime)
        self.assertEqual(sensor.device_class, SENSOR.SensorDeviceClass.ENUM)
        self.assertEqual(set(sensor.options), set(SENSOR.RISK_LEVELS))

        for level in SENSOR.RISK_LEVELS:
            self.runtime.screen_monitor.last_risk_level = level
            self.assertIn(sensor.native_value, sensor.options)


class ScreenMonitorAvailabilityTests(EntityTestCase):
    def test_the_screen_sensors_follow_the_monitor_being_configured(self) -> None:
        entities = [
            SENSOR.ScreenAssessmentSensor(self.runtime),
            SENSOR.ScreenSafetySensor(self.runtime),
            SENSOR.DailyActivitySummarySensor(self.runtime),
            SENSOR.WeeklyActivitySummarySensor(self.runtime),
        ]
        for entity in entities:
            self.assertFalse(entity.available, entity.name)

        self.runtime.screen_monitor = FakeScreenMonitor(configured=False)
        for entity in entities:
            self.assertFalse(entity.available, entity.name)

        self.runtime.screen_monitor = FakeScreenMonitor(configured=True)
        for entity in entities:
            self.assertTrue(entity.available, entity.name)


class DeviceInfoTests(EntityTestCase):
    def test_the_agent_version_is_the_device_firmware_version(self) -> None:
        sensor = SENSOR.ActiveApplicationSensor(self.runtime)
        self.assertIsNone(sensor.device_info["sw_version"])
        self.assertEqual(
            sensor.device_info["identifiers"], {(CONST.DOMAIN, "casey-phone")}
        )
        self.assertEqual(sensor.device_info["model"], "Android Accessibility Agent")

        self.runtime.agent_version = "0.5.5"
        self.runtime.reported_username = "casey"
        self.assertEqual(sensor.device_info["sw_version"], "0.5.5")
        # The account the activity was reported under stays with the activity.
        self.assertEqual(sensor.extra_state_attributes, {"username": "casey"})

    def test_a_new_agent_version_is_pushed_to_the_device_registry(self) -> None:
        # Home Assistant reads device info only when an entity is added, so a
        # version that changes under a running Home Assistant needs a push.
        device = self.hass.device_registry.add(
            "device-1", {(CONST.DOMAIN, "casey-phone")}, sw_version="0.5.4"
        )
        self.runtime.update_activity({"agent_version": "0.5.5"})
        self.assertEqual(
            self.hass.device_registry.updates, [("device-1", {"sw_version": "0.5.5"})]
        )
        self.assertEqual(device.sw_version, "0.5.5")

        # A heartbeat that repeats the same version writes nothing.
        self.runtime.update_activity({"agent_version": "0.5.5"})
        self.assertEqual(len(self.hass.device_registry.updates), 1)

    def test_a_version_for_an_unknown_device_is_not_pushed(self) -> None:
        self.runtime.update_activity({"agent_version": "0.5.5"})
        self.assertEqual(self.hass.device_registry.updates, [])

    def test_the_older_registry_lookup_is_used_when_it_is_all_there_is(self) -> None:
        # Home Assistant before identifiers were scoped to a config entry has
        # no scoped lookup at all, so the fallback has to work.
        self.hass.device_registry.async_get_device_by_identifier = None
        self.hass.device_registry.add(
            "device-1", {(CONST.DOMAIN, "casey-phone")}, sw_version="0.5.4"
        )
        self.runtime.update_activity({"agent_version": "0.5.5"})
        self.assertEqual(
            self.hass.device_registry.updates, [("device-1", {"sw_version": "0.5.5"})]
        )


class PlatformSetupTests(EntityTestCase):
    """Which entities each platform creates for which kind of device."""

    def _runtime(self, entry_id: str, device_id: str, device_type: str):
        runtime = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id=entry_id,
            device_id=device_id,
            device_name=f"{device_id} device",
            windows_username="Casey",
            api_key="existing-key",
            device_type=device_type,
        )
        self.hass.data.setdefault(CONST.DOMAIN, {})[entry_id] = runtime
        return runtime

    async def test_a_phone_gets_the_allowlist_and_installed_apps_entities(self) -> None:
        self._runtime("entry-1", "casey-phone", CONST.DEVICE_TYPE_ANDROID)
        entry = FakeConfigEntry({}, entry_id="entry-1")

        sensors: list = []
        await SENSOR.async_setup_entry(self.hass, entry, sensors.extend)
        self.assertTrue(any(isinstance(e, SENSOR.InstalledAppsSensor) for e in sensors))
        self.assertTrue(any(isinstance(e, SENSOR.NotificationsSensor) for e in sensors))
        self.assertTrue(any(isinstance(e, SENSOR.PendingSetupsSensor) for e in sensors))

        texts: list = []
        await TEXT.async_setup_entry(self.hass, entry, texts.extend)
        self.assertTrue(any(isinstance(e, TEXT.AllowedAppsText) for e in texts))

        buttons: list = []
        await BUTTON.async_setup_entry(self.hass, entry, buttons.extend)
        self.assertTrue(
            any(isinstance(e, BUTTON.RequestLocationButton) for e in buttons)
        )

        trackers: list = []
        await TRACKER.async_setup_entry(self.hass, entry, trackers.extend)
        self.assertTrue(any(isinstance(e, TRACKER.LocationTracker) for e in trackers))

    async def test_a_pc_gets_no_phone_only_entities(self) -> None:
        self._runtime("entry-1", "casey-pc", CONST.DEVICE_TYPE_WINDOWS)
        entry = FakeConfigEntry({}, entry_id="entry-1")

        sensors: list = []
        await SENSOR.async_setup_entry(self.hass, entry, sensors.extend)
        self.assertFalse(any(isinstance(e, SENSOR.InstalledAppsSensor) for e in sensors))
        self.assertFalse(any(isinstance(e, SENSOR.NotificationsSensor) for e in sensors))
        self.assertTrue(any(isinstance(e, SENSOR.PendingSetupsSensor) for e in sensors))

        texts: list = []
        await TEXT.async_setup_entry(self.hass, entry, texts.extend)
        self.assertFalse(any(isinstance(e, TEXT.AllowedAppsText) for e in texts))

        buttons: list = []
        await BUTTON.async_setup_entry(self.hass, entry, buttons.extend)
        self.assertFalse(
            any(isinstance(e, BUTTON.RequestLocationButton) for e in buttons)
        )

        trackers: list = []
        await TRACKER.async_setup_entry(self.hass, entry, trackers.extend)
        self.assertEqual(trackers, [])

    async def test_a_pc_reports_the_portal_status_and_a_phone_the_enforcement_status(self) -> None:
        self._runtime("entry-1", "casey-pc", CONST.DEVICE_TYPE_WINDOWS)
        self._runtime("entry-2", "casey-phone", CONST.DEVICE_TYPE_ANDROID)

        pc_sensors: list = []
        await SENSOR.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id="entry-1"), pc_sensors.extend
        )
        self.assertTrue(any(isinstance(e, SENSOR.PortalStatusSensor) for e in pc_sensors))
        self.assertFalse(
            any(isinstance(e, SENSOR.EnforcementStatusSensor) for e in pc_sensors)
        )

        phone_sensors: list = []
        await SENSOR.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id="entry-2"), phone_sensors.extend
        )
        self.assertTrue(
            any(isinstance(e, SENSOR.EnforcementStatusSensor) for e in phone_sensors)
        )
        self.assertFalse(
            any(isinstance(e, SENSOR.PortalStatusSensor) for e in phone_sensors)
        )

    async def test_only_one_device_creates_the_pending_entities(self) -> None:
        self._runtime("entry-1", "casey-phone", CONST.DEVICE_TYPE_ANDROID)
        self._runtime("entry-2", "casey-pc", CONST.DEVICE_TYPE_WINDOWS)

        first: list = []
        await SENSOR.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id="entry-1"), first.extend
        )
        second: list = []
        await SENSOR.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id="entry-2"), second.extend
        )
        self.assertEqual(
            sum(isinstance(e, SENSOR.PendingSetupsSensor) for e in first), 1
        )
        self.assertEqual(
            sum(isinstance(e, SENSOR.PendingSetupsSensor) for e in second), 0
        )

    async def test_the_button_platform_adds_a_pair_for_each_live_request(self) -> None:
        self._runtime("entry-1", "casey-phone", CONST.DEVICE_TYPE_ANDROID)
        entry = FakeConfigEntry({}, entry_id="entry-1")
        request = await self._pending_request()

        added: list = []
        await BUTTON.async_setup_entry(self.hass, entry, added.extend)
        self.assertTrue(any(isinstance(e, BUTTON.CheckScreenButton) for e in added))
        self.assertEqual(
            sorted(object_id(e) for e in added if isinstance(e, BUTTON.PendingSetupButton)),
            [
                f"rowe_pc_blocker_approve_{request.request_id}",
                f"rowe_pc_blocker_reject_{request.request_id}",
            ],
        )
        # Unloading the owning entry stops the manager.
        self.assertEqual(len(entry.on_unload), 1)
        entry.on_unload[0]()
        self.assertEqual(self.hass.dispatcher[CONST.SIGNAL_PENDING], [])
        self.assertEqual(self.hass.intervals, [])


class PendingSetupsSensorTests(EntityTestCase):
    async def test_lists_the_requests_waiting_for_a_parent(self) -> None:
        sensor = SENSOR.PendingSetupsSensor(self.hass)
        self.assertEqual(sensor.native_value, 0)
        self.assertEqual(sensor.extra_state_attributes, {"requests": []})

        request = await self._pending_request()
        self.assertEqual(sensor.native_value, 1)
        self.assertEqual(
            sensor.extra_state_attributes["requests"],
            [
                {
                    "request_id": request.request_id,
                    "child_name": "Casey",
                    "requested_at": request.requested_at.isoformat(),
                }
            ],
        )

        # An answered request is no longer waiting on a parent.
        await PENDING.async_reject_request(self.hass, request.request_id)
        self.assertEqual(sensor.native_value, 0)

    async def test_the_entity_id_is_the_documented_one(self) -> None:
        sensor = SENSOR.PendingSetupsSensor(self.hass)
        self.assertEqual(object_id(sensor), "rowe_pc_blocker_pending_setups")

    async def test_a_new_request_writes_the_state(self) -> None:
        sensor = self._attach(SENSOR.PendingSetupsSensor(self.hass))
        await sensor.async_added_to_hass()
        await self._pending_request()
        self.assertEqual(sensor.state_writes, 1)


class PendingSetupButtonTests(EntityTestCase):
    async def test_the_entity_ids_are_the_documented_ones(self) -> None:
        request = await self._pending_request()
        approve = BUTTON.PendingSetupButton(self.hass, request, BUTTON.APPROVE)
        reject = BUTTON.PendingSetupButton(self.hass, request, BUTTON.REJECT)
        self.assertEqual(
            object_id(approve), f"rowe_pc_blocker_approve_{request.request_id}"
        )
        self.assertEqual(
            object_id(reject), f"rowe_pc_blocker_reject_{request.request_id}"
        )
        self.assertEqual(approve.extra_state_attributes["child_name"], "Casey")

    async def test_approving_creates_the_phone_and_removes_the_buttons(self) -> None:
        request = await self._pending_request()
        added: list = []
        manager = BUTTON.PendingRequestButtons(self.hass, added.extend)
        self.assertEqual(len(added), 2)
        approve = next(
            button for button in added if button._action == BUTTON.APPROVE
        )
        for button in added:
            button.entity_id = f"button.{object_id(button)}"

        await approve.async_press()
        await self.hass.async_drain()

        created = self.hass.config_entries.flow.created
        self.assertEqual(len(created), 1)
        self.assertEqual(created[0][CONST.CONF_DEVICE_ID], "casey-phone")
        self.assertEqual(created[0][CONST.CONF_DEVICE_TYPE], CONST.DEVICE_TYPE_ANDROID)
        self.assertTrue(created[0][CONST.CONF_API_KEY])
        self.assertEqual(PENDING.awaiting_requests(self.hass), [])
        self.assertTrue(all(button.removed for button in added))
        self.assertEqual(
            self.hass.entity_registry.removed,
            [f"button.{object_id(button)}" for button in added],
        )
        manager.async_stop()

    async def test_an_existing_phone_is_reused_without_a_new_entry(self) -> None:
        self.hass.config_entries.entries.append(
            FakeConfigEntry(dict(PHONE_DATA), entry_id="entry-1")
        )
        request = await self._pending_request()
        added: list = []
        manager = BUTTON.PendingRequestButtons(self.hass, added.extend)
        approve = next(button for button in added if button._action == BUTTON.APPROVE)

        await approve.async_press()
        await self.hass.async_drain()

        self.assertEqual(self.hass.config_entries.flow.created, [])
        self.assertEqual(
            PENDING.pending_store(self.hass).get(
                request.request_id, ha_stub.utcnow()
            ).api_key,
            "existing-key",
        )
        manager.async_stop()

    async def test_an_expired_request_loses_its_buttons(self) -> None:
        request = await self._pending_request()
        added: list = []
        manager = BUTTON.PendingRequestButtons(self.hass, added.extend)
        self.assertEqual(len(added), 2)
        sweep, interval = self.hass.intervals[0]
        self.assertEqual(interval, timedelta(seconds=60))

        request.requested_at = ha_stub.utcnow() - timedelta(
            seconds=PROVISION.PENDING_TTL_SECONDS + 1
        )
        sweep(None)
        await self.hass.async_drain()

        self.assertTrue(all(button.removed for button in added))
        manager.async_stop()
        self.assertEqual(self.hass.intervals, [])
        self.assertEqual(self.hass.dispatcher[CONST.SIGNAL_PENDING], [])

    async def test_rejecting_answers_the_phone_without_creating_anything(self) -> None:
        request = await self._pending_request()
        added: list = []
        manager = BUTTON.PendingRequestButtons(self.hass, added.extend)
        reject = next(button for button in added if button._action == BUTTON.REJECT)

        await reject.async_press()
        await self.hass.async_drain()

        self.assertEqual(self.hass.config_entries.flow.created, [])
        status_code, body = PROVISION.collection_response(
            PENDING.pending_store(self.hass).get(
                request.request_id, ha_stub.utcnow()
            )
        )
        self.assertEqual(status_code, 403)
        self.assertEqual(body["status"], "rejected")
        self.assertEqual(PENDING.awaiting_requests(self.hass), [])
        manager.async_stop()


if __name__ == "__main__":
    unittest.main()
