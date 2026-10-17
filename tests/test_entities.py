"""Entity state and attributes, and the agent stale events.

These tests load the integration modules against the stand-ins in
``ha_stub.py``, so they exercise the real entity classes without a Home
Assistant install.
"""

from __future__ import annotations

import unittest
from datetime import timedelta

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
        self.assertEqual(sensor.extra_state_attributes, {"last_seen": None})

        self.runtime.last_seen = ha_stub.utcnow()
        self.assertTrue(sensor.is_on)
        self.assertEqual(
            sensor.extra_state_attributes["last_seen"],
            self.runtime.last_seen.isoformat(),
        )

        self.runtime.last_seen = ha_stub.utcnow() - timedelta(
            seconds=CONST.ONLINE_TIMEOUT_SECONDS + 1
        )
        self.assertFalse(sensor.is_on)

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
        self.assertTrue(any(isinstance(e, SENSOR.PendingSetupsSensor) for e in sensors))

        texts: list = []
        await TEXT.async_setup_entry(self.hass, entry, texts.extend)
        self.assertTrue(any(isinstance(e, TEXT.AllowedAppsText) for e in texts))

    async def test_a_pc_gets_no_phone_only_entities(self) -> None:
        self._runtime("entry-1", "casey-pc", CONST.DEVICE_TYPE_WINDOWS)
        entry = FakeConfigEntry({}, entry_id="entry-1")

        sensors: list = []
        await SENSOR.async_setup_entry(self.hass, entry, sensors.extend)
        self.assertFalse(any(isinstance(e, SENSOR.InstalledAppsSensor) for e in sensors))
        self.assertTrue(any(isinstance(e, SENSOR.PendingSetupsSensor) for e in sensors))

        texts: list = []
        await TEXT.async_setup_entry(self.hass, entry, texts.extend)
        self.assertFalse(any(isinstance(e, TEXT.AllowedAppsText) for e in texts))

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
