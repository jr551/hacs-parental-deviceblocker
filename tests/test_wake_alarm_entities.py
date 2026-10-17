"""The wake alarm controls a parent sets from the device page.

These load the real entity classes against the stand-ins in ``ha_stub.py``.
The payload assertions go through the API helper the phone's state poll uses,
so a control that only changed an entity but never reached the phone fails.
"""

from __future__ import annotations

import unittest
from datetime import time as dt_time

import ha_stub
from ha_stub import FakeConfigEntry, FakeHass, slugify

CONST = ha_stub.load("const")
RUNTIME = ha_stub.load("runtime")
SWITCH = ha_stub.load("switch")
TIME = ha_stub.load("time")
NUMBER = ha_stub.load("number")
SELECT = ha_stub.load("select")
API = ha_stub.load("api")
WAKE = ha_stub.load("wake_alarm")


class WakeAlarmEntityTestCase(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.hass = FakeHass()
        self.runtime = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id="casey-phone",
            device_name="Casey Phone",
            windows_username="Casey",
            api_key="existing-key",
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        self.hass.data.setdefault(CONST.DOMAIN, {})["entry-1"] = self.runtime

    def _pc(self):
        pc = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-2",
            device_id="casey-pc",
            device_name="Casey PC",
            windows_username="Casey",
            api_key="existing-key",
            device_type=CONST.DEVICE_TYPE_WINDOWS,
        )
        self.hass.data.setdefault(CONST.DOMAIN, {})["entry-2"] = pc
        return pc

    async def _added(self, platform, entry_id: str = "entry-1") -> list:
        entities: list = []
        await platform.async_setup_entry(
            self.hass, FakeConfigEntry({}, entry_id=entry_id), entities.extend
        )
        return entities

    def _forbid_config_entry_writes(self) -> None:
        """An entity write is runtime state, never an options update."""

        def fail(*args, **kwargs):
            self.fail("an entity write must not update the config entry")

        self.hass.config_entries.async_update_entry = fail
        self.hass.config_entries.async_schedule_reload = fail

    def test_the_control_ids_are_the_documented_ones(self) -> None:
        switch = SWITCH.PcWakeAlarmSwitch(self.runtime)
        self.assertEqual(switch.name, "Wake alarm")
        self.assertEqual(switch.unique_id, "casey-phone_wake_alarm")
        # Home Assistant prefixes a has-entity-name entity with its device
        # name, which is what makes the dashboard show the controls together.
        self.assertEqual(
            f"switch.{slugify(f'{self.runtime.device_name} {switch.name}')}",
            "switch.casey_phone_wake_alarm",
        )

        self.assertEqual(
            TIME.WakeAlarmTime(self.runtime).unique_id,
            "casey-phone_wake_alarm_time",
        )
        self.assertEqual(
            SELECT.WakeAlarmDays(self.runtime).unique_id,
            "casey-phone_wake_alarm_days",
        )
        self.assertEqual(
            NUMBER.WakeAlarmVolume(self.runtime).unique_id,
            "casey-phone_wake_alarm_volume",
        )

    async def test_a_phone_gets_all_four_controls(self) -> None:
        switches = await self._added(SWITCH)
        self.assertTrue(any(isinstance(e, SWITCH.PcWakeAlarmSwitch) for e in switches))
        self.assertTrue(any(isinstance(e, SWITCH.PcBlockedSwitch) for e in switches))

        times = await self._added(TIME)
        self.assertTrue(any(isinstance(e, TIME.WakeAlarmTime) for e in times))

        numbers = await self._added(NUMBER)
        self.assertTrue(any(isinstance(e, NUMBER.WakeAlarmVolume) for e in numbers))

        selects = await self._added(SELECT)
        self.assertTrue(any(isinstance(e, SELECT.WakeAlarmDays) for e in selects))

    async def test_a_pc_gets_none_of_them(self) -> None:
        self._pc()

        switches = await self._added(SWITCH, "entry-2")
        self.assertFalse(any(isinstance(e, SWITCH.PcWakeAlarmSwitch) for e in switches))
        self.assertEqual(await self._added(TIME, "entry-2"), [])
        self.assertEqual(await self._added(NUMBER, "entry-2"), [])
        self.assertEqual(await self._added(SELECT, "entry-2"), [])

    async def test_each_control_reaches_the_phone_payload(self) -> None:
        self._forbid_config_entry_writes()
        self.assertEqual(
            API._wake_alarm(self.runtime),
            {
                "enabled": False,
                "hour": 7,
                "minute": 0,
                "days": 0b0011111,
                "volume": 100,
            },
        )

        alarm = SWITCH.PcWakeAlarmSwitch(self.runtime)
        await alarm.async_turn_on()
        self.assertTrue(alarm.is_on)
        self.assertTrue(API._wake_alarm(self.runtime)["enabled"])
        await alarm.async_turn_off()
        self.assertFalse(API._wake_alarm(self.runtime)["enabled"])

        clock = TIME.WakeAlarmTime(self.runtime)
        await clock.async_set_value(dt_time(6, 30))
        self.assertEqual(clock.native_value, dt_time(6, 30))
        self.assertEqual(API._wake_alarm(self.runtime)["hour"], 6)
        self.assertEqual(API._wake_alarm(self.runtime)["minute"], 30)

        days = SELECT.WakeAlarmDays(self.runtime)
        await days.async_select_option(SELECT.WEEKENDS)
        self.assertEqual(days.current_option, SELECT.WEEKENDS)
        self.assertEqual(self.runtime.wake_alarm_days, ["sat", "sun"])
        self.assertEqual(API._wake_alarm(self.runtime)["days"], 0b1100000)
        await days.async_select_option(SELECT.WEEKDAYS)
        self.assertEqual(days.current_option, SELECT.WEEKDAYS)
        self.assertEqual(API._wake_alarm(self.runtime)["days"], 0b0011111)

        volume = NUMBER.WakeAlarmVolume(self.runtime)
        await volume.async_set_native_value(40)
        self.assertEqual(volume.native_value, 40.0)
        self.assertEqual(API._wake_alarm(self.runtime)["volume"], 40)

    async def test_volume_accepts_the_bounds_and_ignores_anything_else(self) -> None:
        volume = NUMBER.WakeAlarmVolume(self.runtime)
        self.assertEqual(volume.native_value, 100.0)
        self.assertEqual(NUMBER.WakeAlarmVolume._attr_native_min_value, 0)
        self.assertEqual(NUMBER.WakeAlarmVolume._attr_native_max_value, 100)
        self.assertEqual(NUMBER.WakeAlarmVolume._attr_native_step, 5)
        self.assertEqual(NUMBER.WakeAlarmVolume._attr_native_unit_of_measurement, "%")
        self.assertEqual(NUMBER.WakeAlarmVolume._attr_mode, NUMBER.NumberMode.SLIDER)

        await volume.async_set_native_value(0)
        self.assertEqual(self.runtime.wake_alarm_volume, 0)
        await volume.async_set_native_value(100)
        self.assertEqual(self.runtime.wake_alarm_volume, 100)

        for rejected in (-5, 101, 37.5, float("nan"), float("inf"), "loud"):
            await volume.async_set_native_value(rejected)
            self.assertEqual(self.runtime.wake_alarm_volume, 100)

    async def test_a_bad_time_is_ignored_and_seconds_are_normalised(self) -> None:
        clock = TIME.WakeAlarmTime(self.runtime)
        await clock.async_set_value(dt_time(6, 45))
        self.assertEqual(clock.native_value, dt_time(6, 45))

        await clock.async_set_value("25:00")
        self.assertEqual(self.runtime.wake_alarm_time, "06:45")
        self.assertEqual(clock.native_value, dt_time(6, 45))

        await clock.async_set_value(dt_time(7, 5, 30))
        self.assertEqual(self.runtime.wake_alarm_time, "07:05")

    async def test_a_non_preset_day_list_reports_custom_and_custom_keeps_it(self) -> None:
        self.runtime.wake_alarm_days = ["mon", "wed", "fri"]
        days = SELECT.WakeAlarmDays(self.runtime)

        self.assertEqual(days.current_option, SELECT.CUSTOM)
        self.assertEqual(
            days.extra_state_attributes, {"days": ["mon", "wed", "fri"]}
        )

        await days.async_select_option(SELECT.CUSTOM)
        self.assertEqual(self.runtime.wake_alarm_days, ["mon", "wed", "fri"])
        self.assertEqual(days.current_option, SELECT.CUSTOM)

        await days.async_select_option("Monthdays")
        self.assertEqual(self.runtime.wake_alarm_days, ["mon", "wed", "fri"])

    def test_the_presets_report_themselves(self) -> None:
        days = SELECT.WakeAlarmDays(self.runtime)
        self.assertEqual(days.current_option, SELECT.WEEKDAYS)
        self.assertEqual(SELECT.WakeAlarmDays._attr_options, list(SELECT.DAY_OPTIONS))

        self.runtime.wake_alarm_days = list(WAKE.WAKE_DAYS)
        self.assertEqual(days.current_option, SELECT.EVERY_DAY)

        self.runtime.wake_alarm_days = ["sat", "sun"]
        self.assertEqual(days.current_option, SELECT.WEEKENDS)

    async def test_the_options_flow_seeds_an_alarm_nobody_has_changed(self) -> None:
        self.hass.config_entries.entries.append(
            FakeConfigEntry(
                {},
                entry_id="entry-1",
                options={
                    WAKE.CONF_WAKE_ALARM_ENABLED: True,
                    WAKE.CONF_WAKE_ALARM_TIME: "06:15",
                    WAKE.CONF_WAKE_ALARM_DAYS: ["sat", "sun"],
                    WAKE.CONF_WAKE_ALARM_VOLUME: 35,
                },
            )
        )
        await self.runtime.async_load()

        self.assertTrue(self.runtime.wake_alarm_enabled)
        self.assertEqual(self.runtime.wake_alarm_time, "06:15")
        self.assertEqual(self.runtime.wake_alarm_days, ["sat", "sun"])
        self.assertEqual(self.runtime.wake_alarm_volume, 35)
        self.assertTrue(API._wake_alarm(self.runtime)["enabled"])

    async def test_a_stored_alarm_wins_over_the_options(self) -> None:
        self.hass.config_entries.entries.append(
            FakeConfigEntry(
                {},
                entry_id="entry-1",
                options={
                    WAKE.CONF_WAKE_ALARM_ENABLED: True,
                    WAKE.CONF_WAKE_ALARM_TIME: "06:15",
                    WAKE.CONF_WAKE_ALARM_DAYS: ["sat", "sun"],
                    WAKE.CONF_WAKE_ALARM_VOLUME: 35,
                },
            )
        )
        self.hass.storage["rowe_pc_blocker.casey-phone"] = {
            "wake_alarm_enabled": False,
            "wake_alarm_time": "08:10",
            "wake_alarm_days": ["sun"],
            "wake_alarm_volume": 20,
        }
        await self.runtime.async_load()

        self.assertFalse(self.runtime.wake_alarm_enabled)
        self.assertEqual(self.runtime.wake_alarm_time, "08:10")
        self.assertEqual(self.runtime.wake_alarm_days, ["sun"])
        self.assertEqual(self.runtime.wake_alarm_volume, 20)

    async def test_a_change_survives_a_restart_through_the_runtime_store(self) -> None:
        await self.runtime.async_load()
        await SWITCH.PcWakeAlarmSwitch(self.runtime).async_turn_on()
        await TIME.WakeAlarmTime(self.runtime).async_set_value(dt_time(6, 5))
        await SELECT.WakeAlarmDays(self.runtime).async_select_option(
            SELECT.WEEKENDS
        )
        await NUMBER.WakeAlarmVolume(self.runtime).async_set_native_value(45)

        stored = self.hass.storage["rowe_pc_blocker.casey-phone"]
        self.assertTrue(stored["wake_alarm_enabled"])
        self.assertEqual(stored["wake_alarm_time"], "06:05")
        self.assertEqual(stored["wake_alarm_days"], ["sat", "sun"])
        self.assertEqual(stored["wake_alarm_volume"], 45)

        restarted = RUNTIME.PcRuntime(
            hass=self.hass,
            entry_id="entry-1",
            device_id="casey-phone",
            device_name="Casey Phone",
            windows_username="Casey",
            api_key="existing-key",
            device_type=CONST.DEVICE_TYPE_ANDROID,
        )
        await restarted.async_load()
        self.assertTrue(restarted.wake_alarm_enabled)
        self.assertEqual(restarted.wake_alarm_time, "06:05")
        self.assertEqual(restarted.wake_alarm_days, ["sat", "sun"])
        self.assertEqual(restarted.wake_alarm_volume, 45)


if __name__ == "__main__":
    unittest.main()
