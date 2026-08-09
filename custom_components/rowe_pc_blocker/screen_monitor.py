"""Optional VNC screenshot assessment through an OpenAI-compatible provider."""

from __future__ import annotations

import asyncio
import base64
from dataclasses import dataclass
from datetime import datetime, timedelta
from io import BytesIO
import json
import random
from typing import Callable, Mapping
from urllib.parse import urlsplit, urlunsplit

from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util
from PIL import Image

from .activity_journal import (
    HIGH_RISK_SCORE,
    SAFETY_OUTPUT_INSTRUCTION,
    ActivityRecord,
    ScreenAssessment,
    build_activity_report,
    parse_provider_assessment,
    prune_records,
)

from .const import (
    CONF_KILO_API_KEY,
    CONF_KILO_BASE_URL,
    CONF_KILO_MODEL,
    CONF_SCREEN_FIXED_INTERVAL,
    CONF_SCREEN_MONITOR_ENABLED,
    CONF_SCREEN_PROMPT,
    CONF_SCREEN_RANDOM_MAX,
    CONF_SCREEN_RANDOM_MIN,
    CONF_SCREEN_SCHEDULE_MODE,
    CONF_VNC_HOST,
    CONF_VNC_PASSWORD,
    CONF_VNC_PORT,
    DEFAULT_KILO_BASE_URL,
    DEFAULT_KILO_MODEL,
    DEFAULT_SCREEN_FIXED_INTERVAL,
    DEFAULT_SCREEN_PROMPT,
    DEFAULT_SCREEN_RANDOM_MAX,
    DEFAULT_SCREEN_RANDOM_MIN,
    DEFAULT_VNC_PORT,
    DOMAIN,
    MAX_SCREEN_INTERVAL_MINUTES,
    MIN_SCREEN_INTERVAL_MINUTES,
    SCREEN_SCHEDULE_FIXED,
    SCREEN_SCHEDULE_MANUAL,
    SCREEN_SCHEDULE_RANDOM,
)
from .vnc import VncAuthenticationError, VncError, VncFrame, async_capture_vnc_frame


MAX_SUMMARY_LENGTH = 1000
MAX_PROVIDER_RESPONSE_BYTES = 256 * 1024


@dataclass(frozen=True, slots=True)
class ScreenMonitorSettings:
    """Validated monitor settings sourced from config-entry options."""

    enabled: bool
    vnc_host: str
    vnc_port: int
    vnc_password: str
    schedule_mode: str
    fixed_interval_minutes: int
    random_min_minutes: int
    random_max_minutes: int
    kilo_api_key: str
    kilo_base_url: str
    kilo_model: str
    prompt: str

    @classmethod
    def from_options(cls, options: Mapping) -> "ScreenMonitorSettings":
        return cls(
            enabled=bool(options.get(CONF_SCREEN_MONITOR_ENABLED, False)),
            vnc_host=str(options.get(CONF_VNC_HOST, "")).strip(),
            vnc_port=int(options.get(CONF_VNC_PORT, DEFAULT_VNC_PORT)),
            vnc_password=str(options.get(CONF_VNC_PASSWORD, "")),
            schedule_mode=str(
                options.get(CONF_SCREEN_SCHEDULE_MODE, SCREEN_SCHEDULE_MANUAL)
            ),
            fixed_interval_minutes=int(
                options.get(CONF_SCREEN_FIXED_INTERVAL, DEFAULT_SCREEN_FIXED_INTERVAL)
            ),
            random_min_minutes=int(
                options.get(CONF_SCREEN_RANDOM_MIN, DEFAULT_SCREEN_RANDOM_MIN)
            ),
            random_max_minutes=int(
                options.get(CONF_SCREEN_RANDOM_MAX, DEFAULT_SCREEN_RANDOM_MAX)
            ),
            kilo_api_key=str(options.get(CONF_KILO_API_KEY, "")),
            kilo_base_url=str(
                options.get(CONF_KILO_BASE_URL, DEFAULT_KILO_BASE_URL)
            ).strip(),
            kilo_model=str(options.get(CONF_KILO_MODEL, DEFAULT_KILO_MODEL)).strip(),
            prompt=str(options.get(CONF_SCREEN_PROMPT, DEFAULT_SCREEN_PROMPT)).strip(),
        )

    @property
    def configured(self) -> bool:
        return bool(
            self.enabled
            and self.vnc_host
            and self.vnc_password
            and self.kilo_api_key
            and self.kilo_model
            and self.prompt
        )


class ScreenMonitor:
    """Capture, assess, and schedule ephemeral screen images."""

    def __init__(
        self,
        hass,
        device_id: str,
        device_name: str,
        options: Mapping,
        notify: Callable[[], None],
    ) -> None:
        self.hass = hass
        self.device_id = device_id
        self.device_name = device_name
        self.settings = ScreenMonitorSettings.from_options(options)
        self._notify = notify
        self._schedule_task: asyncio.Task | None = None
        self._lock = asyncio.Lock()
        self._history_store = Store(
            hass, 1, f"{DOMAIN}.screen_history.{device_id}"
        )
        self._history_loaded = False
        self.history: list[ActivityRecord] = []
        self.status = "disabled" if not self.settings.enabled else "idle"
        self.last_summary = ""
        self.last_category = "unknown"
        self.last_risk_score = 0
        self.last_risk_level = "none"
        self.last_reasons: tuple[str, ...] = ()
        self.last_error = ""
        self.last_checked_at: datetime | None = None
        self.last_attempted_at: datetime | None = None
        self.last_trigger = ""
        self.next_check_at: datetime | None = None

    async def async_start(self) -> None:
        """Start fixed or random scheduling without capturing immediately."""
        await self._async_load_history()
        if not self.settings.enabled:
            self.status = "disabled"
            self._notify()
            return
        if not self.settings.configured:
            self.status = "not_configured"
            self._notify()
            return
        self.status = "idle"
        if self.settings.schedule_mode in (
            SCREEN_SCHEDULE_FIXED,
            SCREEN_SCHEDULE_RANDOM,
        ):
            self._schedule_task = self.hass.async_create_background_task(
                self._async_schedule_loop(),
                f"{DOMAIN}_{self.device_id}_screen_monitor",
            )
        self._notify()

    async def async_stop(self) -> None:
        """Stop future captures and wait for the scheduler to exit."""
        task = self._schedule_task
        self._schedule_task = None
        self.next_check_at = None
        if task is not None:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

    async def async_check_now(self, trigger: str = "manual") -> bool:
        """Capture one ephemeral image and publish its text assessment."""
        if not self.settings.configured:
            self.status = "not_configured"
            self.last_error = "Screen monitoring is disabled or incomplete"
            self._notify()
            return False
        if self._lock.locked():
            self.last_error = "A screen check is already running"
            self._notify()
            return False

        async with self._lock:
            self.status = "checking"
            self.last_error = ""
            self.last_attempted_at = dt_util.utcnow()
            self.last_trigger = trigger[:32]
            self._notify()
            try:
                frame = await async_capture_vnc_frame(
                    self.settings.vnc_host,
                    self.settings.vnc_port,
                    self.settings.vnc_password,
                )
                jpeg = await self.hass.async_add_executor_job(_frame_to_jpeg, frame)
                assessment = await self._async_assess(jpeg)
                self.last_summary = assessment.summary[:MAX_SUMMARY_LENGTH]
                self.last_category = assessment.category
                self.last_risk_score = assessment.risk_score
                self.last_risk_level = assessment.risk_level
                self.last_reasons = assessment.reasons
                self.last_checked_at = dt_util.utcnow()
                self.history.append(
                    ActivityRecord(
                        checked_at=self.last_checked_at,
                        summary=self.last_summary,
                        category=self.last_category,
                        risk_score=self.last_risk_score,
                        reasons=self.last_reasons,
                    )
                )
                self.history = prune_records(self.history, self.last_checked_at)
                await self._history_store.async_save(
                    {"records": [record.as_dict() for record in self.history]}
                )
                self.status = "idle"
                event_data = {
                    "device_id": self.device_id,
                    "device_name": self.device_name,
                    "summary": self.last_summary,
                    "category": self.last_category,
                    "risk_score": self.last_risk_score,
                    "risk_level": self.last_risk_level,
                    "reasons": list(self.last_reasons),
                    "checked_at": self.last_checked_at.isoformat(),
                    "trigger": self.last_trigger,
                    "images_retained": False,
                }
                self.hass.bus.async_fire(f"{DOMAIN}_screen_assessed", event_data)
                if self.last_risk_score >= HIGH_RISK_SCORE:
                    self.hass.bus.async_fire(
                        f"{DOMAIN}_safety_alert", event_data
                    )
                return True
            except VncAuthenticationError:
                self.last_error = "VNC viewer authentication failed"
            except VncError as err:
                self.last_error = str(err)[:255]
            except ProviderError as err:
                self.last_error = str(err)[:255]
            except Exception:
                self.last_error = "The screen check failed unexpectedly"
            finally:
                if self.status == "checking":
                    self.status = "error"
                self._notify()
            return False

    async def _async_schedule_loop(self) -> None:
        try:
            while True:
                delay_minutes = self._next_delay_minutes()
                self.next_check_at = dt_util.utcnow() + timedelta(minutes=delay_minutes)
                self._notify()
                await asyncio.sleep(delay_minutes * 60)
                self.next_check_at = None
                await self.async_check_now("scheduled")
        except asyncio.CancelledError:
            raise
        finally:
            self.next_check_at = None
            self._notify()

    def _next_delay_minutes(self) -> int:
        if self.settings.schedule_mode == SCREEN_SCHEDULE_RANDOM:
            low = max(MIN_SCREEN_INTERVAL_MINUTES, self.settings.random_min_minutes)
            high = min(MAX_SCREEN_INTERVAL_MINUTES, self.settings.random_max_minutes)
            return random.SystemRandom().randint(low, max(low, high))
        return max(
            MIN_SCREEN_INTERVAL_MINUTES,
            min(MAX_SCREEN_INTERVAL_MINUTES, self.settings.fixed_interval_minutes),
        )

    async def _async_assess(self, jpeg: bytes) -> ScreenAssessment:
        endpoint = _chat_completions_url(self.settings.kilo_base_url)
        encoded = base64.b64encode(jpeg).decode("ascii")
        request = {
            "model": self.settings.kilo_model,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": (
                                self.settings.prompt
                                + "\n\n"
                                + SAFETY_OUTPUT_INSTRUCTION
                            ),
                        },
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:image/jpeg;base64,{encoded}",
                                "detail": "low",
                            },
                        },
                    ],
                }
            ],
            "max_tokens": 360,
            "temperature": 0.1,
            "stream": False,
            "user": self.device_id[:64],
        }
        session = async_get_clientsession(self.hass)
        try:
            async with asyncio.timeout(60):
                async with session.post(
                    endpoint,
                    headers={
                        "Authorization": f"Bearer {self.settings.kilo_api_key}",
                        "Content-Type": "application/json",
                        "x-kilocode-mode": "general",
                    },
                    json=request,
                ) as response:
                    body = await response.content.read(MAX_PROVIDER_RESPONSE_BYTES + 1)
                    if len(body) > MAX_PROVIDER_RESPONSE_BYTES:
                        raise ProviderError("The AI provider response was too large")
                    if response.status < 200 or response.status >= 300:
                        raise ProviderError(
                            f"The AI provider returned HTTP {response.status}"
                        )
        except TimeoutError as err:
            raise ProviderError("The AI provider request timed out") from err
        except ProviderError:
            raise
        except Exception as err:
            raise ProviderError("Could not reach the AI provider") from err

        try:
            payload = json.loads(body)
            content = payload["choices"][0]["message"]["content"]
            if isinstance(content, list):
                content = " ".join(
                    str(item.get("text", ""))
                    for item in content
                    if isinstance(item, dict)
                )
            assessment = parse_provider_assessment(str(content))
        except (KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError) as err:
            raise ProviderError("The AI provider returned an invalid response") from err
        return assessment

    async def _async_load_history(self) -> None:
        if self._history_loaded:
            return
        data = await self._history_store.async_load() or {}
        records = []
        for value in data.get("records", []):
            if isinstance(value, dict):
                record = ActivityRecord.from_dict(value)
                if record is not None:
                    records.append(record)
        self.history = prune_records(records, dt_util.utcnow())
        if self.history:
            latest = self.history[-1]
            self.last_summary = latest.summary
            self.last_category = latest.category
            self.last_risk_score = latest.risk_score
            self.last_risk_level = latest.risk_level
            self.last_reasons = latest.reasons
            self.last_checked_at = latest.checked_at
        self._history_loaded = True

    def activity_report(self, period: str) -> dict:
        """Return a text-only today or rolling-week report."""
        return build_activity_report(
            self.history,
            child_name=self.device_name.removesuffix(" PC"),
            now=dt_util.now(),
            period=period,
        )


class ProviderError(Exception):
    """A safe-to-display provider failure."""


def _frame_to_jpeg(frame: VncFrame) -> bytes:
    image = Image.frombytes(
        "RGB", (frame.width, frame.height), frame.bgra, "raw", "BGRX"
    )
    image.thumbnail((1920, 1080), Image.Resampling.LANCZOS)
    output = BytesIO()
    image.save(output, format="JPEG", quality=72, optimize=True)
    return output.getvalue()


def _chat_completions_url(base_url: str) -> str:
    parsed = urlsplit(base_url.strip())
    if parsed.scheme != "https" or not parsed.netloc:
        raise ProviderError("The AI provider base URL is invalid")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ProviderError("The AI provider base URL must not contain credentials or a query")
    path = parsed.path.rstrip("/")
    if not path.endswith("/chat/completions"):
        path += "/chat/completions"
    return urlunsplit((parsed.scheme, parsed.netloc, path, "", ""))
