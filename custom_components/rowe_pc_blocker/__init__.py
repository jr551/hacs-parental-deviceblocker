"""Parental Device Blocker integration."""

from __future__ import annotations

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers import config_validation as cv

from .api import _portal_context, async_register_api
from .challenge import MAX_CHALLENGE_PHRASE_LENGTH, async_register_services
from .const import (
    CONF_API_KEY,
    CONF_DEVICE_ID,
    CONF_DEVICE_NAME,
    CONF_DEVICE_TYPE,
    CONF_DEVICES,
    CONF_WINDOWS_USERNAME,
    DOMAIN,
    DEVICE_TYPE_WINDOWS,
    PLATFORMS,
)
from .evidence import async_prune_evidence
from .migration import ENTRY_VERSION, migrate_options
from .pending import async_load_pending, release_pending_entities
from .runtime import PcRuntime
from .screen_monitor import ScreenMonitor

SEND_CHALLENGE_SCHEMA = vol.Schema(
    {
        vol.Required("phrase"): vol.All(
            cv.string, vol.Length(min=1, max=MAX_CHALLENGE_PHRASE_LENGTH)
        ),
        vol.Required("entity_id"): cv.entity_ids,
    }
)

CLEAR_CHALLENGE_SCHEMA = vol.Schema({vol.Required("entity_id"): cv.entity_ids})

DEVICE_SCHEMA = vol.Schema(
    {
        vol.Required(CONF_DEVICE_ID): cv.string,
        vol.Required(CONF_DEVICE_NAME): cv.string,
        vol.Optional(CONF_DEVICE_TYPE, default=DEVICE_TYPE_WINDOWS): vol.In(
            [DEVICE_TYPE_WINDOWS, "android"]
        ),
        vol.Required(CONF_WINDOWS_USERNAME): cv.string,
        vol.Required(CONF_API_KEY): cv.string,
    }
)

CONFIG_SCHEMA = vol.Schema(
    {DOMAIN: vol.Schema({vol.Required(CONF_DEVICES): [DEVICE_SCHEMA]})},
    extra=vol.ALLOW_EXTRA,
)


async def async_migrate_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Drop options from removed features and bump the entry version."""
    if entry.version >= ENTRY_VERSION:
        return True
    hass.config_entries.async_update_entry(
        entry,
        version=ENTRY_VERSION,
        options=migrate_options(entry.options),
    )
    return True


async def async_setup(hass: HomeAssistant, config: dict) -> bool:
    """Set up API routes."""
    async_register_api(hass)
    async_register_services(hass, SEND_CHALLENGE_SCHEMA, CLEAR_CHALLENGE_SCHEMA)
    for device in config.get(DOMAIN, {}).get(CONF_DEVICES, []):
        hass.async_create_task(
            hass.config_entries.flow.async_init(
                DOMAIN,
                context={"source": "import"},
                data=dict(device),
            )
        )
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up one PC."""
    async_register_api(hass)
    async_register_services(hass, SEND_CHALLENGE_SCHEMA, CLEAR_CHALLENGE_SCHEMA)
    await async_load_pending(hass)
    # A photo that outlived its window must go even if nothing new arrives.
    await async_prune_evidence(hass)
    runtime = PcRuntime(
        hass=hass,
        entry_id=entry.entry_id,
        device_id=entry.data[CONF_DEVICE_ID],
        device_name=entry.data[CONF_DEVICE_NAME],
        windows_username=entry.data.get(CONF_WINDOWS_USERNAME, ""),
        api_key=entry.data[CONF_API_KEY],
        device_type=entry.data.get(CONF_DEVICE_TYPE, DEVICE_TYPE_WINDOWS),
    )
    await runtime.async_load()
    runtime.screen_monitor = ScreenMonitor(
        hass,
        runtime.device_id,
        runtime.device_name,
        entry.options,
        runtime.notify,
    )
    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = runtime
    entry.async_on_unload(entry.add_update_listener(_async_reload_entry))

    async def _release_rejected_reward(event) -> None:
        """Allow a corrected retry when the chore manager rejects a portal purchase."""
        reward_id = str(event.data.get("reward_id", ""))
        child_id = str(event.data.get("child_id", ""))
        if not reward_id or not child_id:
            return
        context = _portal_context(hass, runtime)
        if context is not None and context[0].child_id == child_id:
            await runtime.async_release_portal_reward(reward_id)

    entry.async_on_unload(
        hass.bus.async_listen("taskmate_reward_rejected", _release_rejected_reward)
    )
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    await runtime.screen_monitor.async_start()
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload one PC."""
    runtime = hass.data[DOMAIN][entry.entry_id]
    if runtime.screen_monitor is not None:
        await runtime.screen_monitor.async_stop()
    unloaded = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unloaded:
        hass.data[DOMAIN].pop(entry.entry_id, None)
        if release_pending_entities(hass, entry.entry_id):
            _async_rehome_pending_entities(hass)
    elif runtime.screen_monitor is not None:
        await runtime.screen_monitor.async_start()
    return unloaded


def _async_rehome_pending_entities(hass: HomeAssistant) -> None:
    """Hand the integration-wide pending entities to a surviving entry.

    They are created by whichever entry loads first, so deleting that entry
    would otherwise take the pending-setups card with it. Reloading one of the
    remaining entries gives the claim to that entry instead.
    """
    if hass.is_stopping:
        return
    remaining = [
        entry.entry_id
        for entry in hass.config_entries.async_entries(DOMAIN)
        if entry.entry_id in hass.data.get(DOMAIN, {})
    ]
    if remaining:
        hass.config_entries.async_schedule_reload(remaining[0])


async def _async_reload_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Apply updated optional monitoring settings."""
    await hass.config_entries.async_reload(entry.entry_id)
