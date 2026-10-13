"""Configuration flow for Parental Device Blocker."""

from __future__ import annotations

import re
import secrets
from urllib.parse import urlsplit

import voluptuous as vol

from homeassistant import config_entries
from homeassistant.data_entry_flow import FlowResult
from homeassistant.helpers import selector

from .const import (
    CONF_API_KEY,
    CONF_CLEAR_PARENT_PIN,
    CONF_CLEAR_SCREEN_CREDENTIALS,
    CONF_DEVICE_ID,
    CONF_DEVICE_NAME,
    CONF_DEVICE_TYPE,
    CONF_KILO_API_KEY,
    CONF_KILO_BASE_URL,
    CONF_KILO_MODEL,
    CONF_PARENT_PIN,
    CONF_SCREEN_FIXED_INTERVAL,
    CONF_SCREEN_MONITOR_ENABLED,
    CONF_SCREEN_PROMPT,
    CONF_SCREEN_RANDOM_MAX,
    CONF_SCREEN_RANDOM_MIN,
    CONF_SCREEN_SCHEDULE_MODE,
    CONF_VNC_HOST,
    CONF_VNC_PASSWORD,
    CONF_VNC_PORT,
    CONF_WINDOWS_USERNAME,
    DEFAULT_KILO_BASE_URL,
    DEFAULT_KILO_MODEL,
    DEFAULT_SCREEN_FIXED_INTERVAL,
    DEFAULT_SCREEN_PROMPT,
    DEFAULT_SCREEN_RANDOM_MAX,
    DEFAULT_SCREEN_RANDOM_MIN,
    DEFAULT_VNC_PORT,
    DOMAIN,
    DEVICE_TYPE_ANDROID,
    DEVICE_TYPE_WINDOWS,
    LEGACY_S3_OPTION_KEYS,
    MAX_SCREEN_INTERVAL_MINUTES,
    MIN_PARENT_PIN_LENGTH,
    MIN_SCREEN_INTERVAL_MINUTES,
    SCREEN_SCHEDULE_MANUAL,
    SCREEN_SCHEDULE_MODES,
)


class ParentalDeviceBlockerConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Configure one Windows PC or Android phone."""

    VERSION = 2

    @staticmethod
    def async_get_options_flow(config_entry) -> config_entries.OptionsFlow:
        """Return the optional VNC monitoring flow."""
        return ParentalDeviceBlockerOptionsFlow()

    async def _create_or_update(self, data: dict, *, from_import: bool) -> FlowResult:
        device_id = re.sub(r"[^a-z0-9_-]", "", data[CONF_DEVICE_ID].lower())
        if not device_id:
            return self.async_abort(reason="invalid_device_id")
        await self.async_set_unique_id(device_id)
        if from_import:
            self._abort_if_unique_id_configured(updates=data)
        else:
            self._abort_if_unique_id_configured()
        data = dict(data)
        data[CONF_DEVICE_ID] = device_id
        if not str(data.get(CONF_API_KEY, "")).strip():
            return self.async_show_form(
                step_id="user",
                data_schema=_user_schema(data),
                errors={CONF_API_KEY: "api_key_required"},
            )
        return self.async_create_entry(title=data[CONF_DEVICE_NAME], data=data)

    async def async_step_import(self, user_input: dict) -> FlowResult:
        """Import one PC from YAML."""
        return await self._create_or_update(user_input, from_import=True)

    async def async_step_user(self, user_input: dict | None = None) -> FlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            candidate = re.sub(r"[^a-z0-9_-]", "", user_input[CONF_DEVICE_ID].lower())
            if not candidate:
                errors[CONF_DEVICE_ID] = "invalid_device_id"
            elif not str(user_input.get(CONF_API_KEY, "")).strip():
                errors[CONF_API_KEY] = "api_key_required"
            else:
                return await self._create_or_update(user_input, from_import=False)

        return self.async_show_form(
            step_id="user",
            data_schema=_user_schema(user_input or {}),
            errors=errors,
        )


def _user_schema(defaults: dict) -> vol.Schema:
    return vol.Schema(
        {
            vol.Required(
                CONF_DEVICE_ID, default=defaults.get(CONF_DEVICE_ID, "child-pc")
            ): str,
            vol.Required(
                CONF_DEVICE_NAME, default=defaults.get(CONF_DEVICE_NAME, "Child PC")
            ): str,
            vol.Required(
                CONF_DEVICE_TYPE,
                default=defaults.get(CONF_DEVICE_TYPE, DEVICE_TYPE_WINDOWS),
            ): vol.In(
                {
                    DEVICE_TYPE_WINDOWS: "Windows PC",
                    DEVICE_TYPE_ANDROID: "Android phone",
                }
            ),
            vol.Required(
                CONF_WINDOWS_USERNAME, default=defaults.get(CONF_WINDOWS_USERNAME, "child")
            ): str,
            vol.Required(
                CONF_API_KEY,
                default=defaults.get(CONF_API_KEY, secrets.token_urlsafe(32)),
            ): str,
        }
    )


class ParentalDeviceBlockerOptionsFlow(config_entries.OptionsFlow):
    """Configure optional parent controls and screen assessment."""

    async def async_step_init(self, user_input: dict | None = None) -> FlowResult:
        current = {
            k: v for k, v in self.config_entry.options.items() if k not in LEGACY_S3_OPTION_KEYS
        }
        errors: dict[str, str] = {}
        if user_input is not None:
            data = dict(user_input)
            clear_credentials = bool(data.pop(CONF_CLEAR_SCREEN_CREDENTIALS, False))
            clear_parent_pin = bool(data.pop(CONF_CLEAR_PARENT_PIN, False))
            if clear_credentials:
                data[CONF_VNC_PASSWORD] = ""
                data[CONF_KILO_API_KEY] = ""
            else:
                for secret_key in (CONF_VNC_PASSWORD, CONF_KILO_API_KEY):
                    if not data.get(secret_key):
                        data[secret_key] = current.get(secret_key, "")

            if clear_parent_pin:
                data[CONF_PARENT_PIN] = ""
            elif not data.get(CONF_PARENT_PIN):
                data[CONF_PARENT_PIN] = current.get(CONF_PARENT_PIN, "")
            elif len(str(data[CONF_PARENT_PIN]).strip()) < MIN_PARENT_PIN_LENGTH:
                errors[CONF_PARENT_PIN] = "parent_pin_too_short"
            else:
                data[CONF_PARENT_PIN] = str(data[CONF_PARENT_PIN]).strip()

            if data[CONF_SCREEN_RANDOM_MIN] > data[CONF_SCREEN_RANDOM_MAX]:
                errors[CONF_SCREEN_RANDOM_MAX] = "random_range"
            if not _valid_provider_url(data[CONF_KILO_BASE_URL]):
                errors[CONF_KILO_BASE_URL] = "invalid_provider_url"
            if data[CONF_SCREEN_MONITOR_ENABLED]:
                if not str(data[CONF_VNC_HOST]).strip():
                    errors[CONF_VNC_HOST] = "required_when_enabled"
                if not data[CONF_VNC_PASSWORD]:
                    errors[CONF_VNC_PASSWORD] = "required_when_enabled"
                if not data[CONF_KILO_API_KEY]:
                    errors[CONF_KILO_API_KEY] = "required_when_enabled"
                if not str(data[CONF_KILO_MODEL]).strip():
                    errors[CONF_KILO_MODEL] = "required_when_enabled"
                if not str(data[CONF_SCREEN_PROMPT]).strip():
                    errors[CONF_SCREEN_PROMPT] = "required_when_enabled"

            if not errors:
                data[CONF_VNC_HOST] = str(data[CONF_VNC_HOST]).strip()
                data[CONF_KILO_BASE_URL] = str(data[CONF_KILO_BASE_URL]).strip()
                data[CONF_KILO_MODEL] = str(data[CONF_KILO_MODEL]).strip()
                data[CONF_SCREEN_PROMPT] = str(data[CONF_SCREEN_PROMPT]).strip()
                return self.async_create_entry(title="", data=data)

            for key, value in data.items():
                if key not in (CONF_VNC_PASSWORD, CONF_KILO_API_KEY, CONF_PARENT_PIN):
                    current[key] = value

        password = selector.TextSelector(
            selector.TextSelectorConfig(type=selector.TextSelectorType.PASSWORD)
        )
        schema = vol.Schema(
            {
                vol.Required(
                    CONF_SCREEN_MONITOR_ENABLED,
                    default=current.get(CONF_SCREEN_MONITOR_ENABLED, False),
                ): bool,
                vol.Required(
                    CONF_VNC_HOST, default=current.get(CONF_VNC_HOST, "")
                ): str,
                vol.Required(
                    CONF_VNC_PORT, default=current.get(CONF_VNC_PORT, DEFAULT_VNC_PORT)
                ): vol.All(vol.Coerce(int), vol.Range(min=1, max=65535)),
                vol.Required(CONF_VNC_PASSWORD, default=""): password,
                vol.Required(
                    CONF_SCREEN_SCHEDULE_MODE,
                    default=current.get(
                        CONF_SCREEN_SCHEDULE_MODE, SCREEN_SCHEDULE_MANUAL
                    ),
                ): vol.In(SCREEN_SCHEDULE_MODES),
                vol.Required(
                    CONF_SCREEN_FIXED_INTERVAL,
                    default=current.get(
                        CONF_SCREEN_FIXED_INTERVAL, DEFAULT_SCREEN_FIXED_INTERVAL
                    ),
                ): vol.All(
                    vol.Coerce(int),
                    vol.Range(
                        min=MIN_SCREEN_INTERVAL_MINUTES,
                        max=MAX_SCREEN_INTERVAL_MINUTES,
                    ),
                ),
                vol.Required(
                    CONF_SCREEN_RANDOM_MIN,
                    default=current.get(
                        CONF_SCREEN_RANDOM_MIN, DEFAULT_SCREEN_RANDOM_MIN
                    ),
                ): vol.All(
                    vol.Coerce(int),
                    vol.Range(
                        min=MIN_SCREEN_INTERVAL_MINUTES,
                        max=MAX_SCREEN_INTERVAL_MINUTES,
                    ),
                ),
                vol.Required(
                    CONF_SCREEN_RANDOM_MAX,
                    default=current.get(
                        CONF_SCREEN_RANDOM_MAX, DEFAULT_SCREEN_RANDOM_MAX
                    ),
                ): vol.All(
                    vol.Coerce(int),
                    vol.Range(
                        min=MIN_SCREEN_INTERVAL_MINUTES,
                        max=MAX_SCREEN_INTERVAL_MINUTES,
                    ),
                ),
                vol.Required(CONF_KILO_API_KEY, default=""): password,
                vol.Required(CONF_PARENT_PIN, default=""): password,
                vol.Required(CONF_CLEAR_PARENT_PIN, default=False): bool,
                vol.Required(CONF_CLEAR_SCREEN_CREDENTIALS, default=False): bool,
                vol.Required(
                    CONF_KILO_BASE_URL,
                    default=current.get(CONF_KILO_BASE_URL, DEFAULT_KILO_BASE_URL),
                ): str,
                vol.Required(
                    CONF_KILO_MODEL,
                    default=current.get(CONF_KILO_MODEL, DEFAULT_KILO_MODEL),
                ): str,
                vol.Required(
                    CONF_SCREEN_PROMPT,
                    default=current.get(CONF_SCREEN_PROMPT, DEFAULT_SCREEN_PROMPT),
                ): selector.TextSelector(
                    selector.TextSelectorConfig(multiline=True)
                ),
            }
        )
        return self.async_show_form(
            step_id="init", data_schema=schema, errors=errors
        )


def _valid_provider_url(value: str) -> bool:
    try:
        parsed = urlsplit(str(value).strip())
    except ValueError:
        return False
    return bool(
        parsed.scheme == "https"
        and parsed.netloc
        and not parsed.username
        and not parsed.password
        and not parsed.query
        and not parsed.fragment
    )
