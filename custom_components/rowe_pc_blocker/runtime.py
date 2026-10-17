"""In-memory runtime state for a configured PC."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
import asyncio
import secrets
from typing import TYPE_CHECKING

from homeassistant.core import HomeAssistant
from homeassistant.helpers import device_registry
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from .agent_status import (
    AGENT_RECOVERED,
    AGENT_STALE,
    agent_is_online,
    agent_stale_transition,
    is_portal_error_status,
    resolve_ui_error,
    resolve_ui_status,
    should_notify_enforcement_degraded,
)
from .apps import parse_installed_apps
from .const import (
    DEFAULT_MESSAGE,
    DOMAIN,
    EVENT_AGENT_RECOVERED,
    EVENT_AGENT_STALE,
    EXTENSION_COOLDOWN_SECONDS,
    EXTENSION_SECONDS,
    INITIAL_GRACE_SECONDS,
    ONLINE_TIMEOUT_SECONDS,
    PARENT_OVERRIDE_MINUTES,
    POINTS_HOUR_MINUTES,
    SIGNAL_UPDATE,
)
from .location import (
    parse_accuracy,
    parse_coordinates,
    parse_fix_age,
    parse_provider,
    request_is_live,
)
from .notifications import NotificationDump, is_newer

if TYPE_CHECKING:
    from .screen_monitor import ScreenMonitor

# The four states the challenge sensor reports. A child fills the phrase in
# (``pending``), sends the photo (``submitted``), and a parent approves it
# (``approved``); ``approved`` stays until a parent clears the challenge or
# sends a new one, so the dashboard can show that the chore was done.
CHALLENGE_NONE = "none"
CHALLENGE_PENDING = "pending"
CHALLENGE_SUBMITTED = "submitted"
CHALLENGE_APPROVED = "approved"

# A submitted challenge approves itself after this long, so a parent who is
# away from their phone cannot leave a child locked indefinitely.
CHALLENGE_AUTO_APPROVE_SECONDS = 2 * 60 * 60


@dataclass(slots=True)
class PcRuntime:
    """State shared by the API and entity platforms."""

    hass: HomeAssistant
    entry_id: str
    device_id: str
    device_name: str
    windows_username: str
    api_key: str
    device_type: str = "windows"
    blocked: bool = False
    message: str = DEFAULT_MESSAGE
    last_seen: datetime | None = None
    active_application: str = ""
    active_window: str = ""
    reported_username: str = ""
    agent_version: str = ""
    ui_status: str = "unknown"
    ui_error: str = ""
    ui_reported_at: datetime | None = None
    portal_error_notified_at: datetime | None = None
    enforcement_degraded_notified_at: datetime | None = None
    block_state_at_last_activity: bool = False
    # True between the stale event and the agent's next check-in, so one
    # offline episode fires exactly one event.
    agent_stale: bool = False
    # On-demand position. The phone no longer reports one on every heartbeat:
    # a parent asks, the request rides the next state poll, and the phone posts
    # one fix back. The fix is deliberately not persisted — a child's position
    # does not belong in the runtime store — so the tracker is unknown again
    # after a restart until the next answer.
    latitude: float | None = None
    longitude: float | None = None
    location_accuracy: float | None = None
    # How old the phone said the fix already was when it answered. The tracker
    # adds the time since it arrived, so the age a parent sees is the age of
    # the position, not of the answer.
    location_fix_age_seconds: int | None = None
    location_provider: str = ""
    location_reported_at: datetime | None = None
    # The pending request: an empty id means there is nothing to answer.
    location_request_id: str = ""
    location_requested_at: datetime | None = None
    # The parent's comma-separated allowlist for this phone, and the apps the
    # phone last reported. Both are empty for a PC.
    allowed_apps: str = ""
    installed_apps: list[dict] = field(default_factory=list)
    # A chore challenge: the phone stays locked until the child types the
    # phrase back and a parent approves the photo. ``challenge_id`` is empty
    # when there is no challenge; ``challenge_submitted_at`` is set when the
    # child sends it, and ``challenge_approved_at`` when a parent accepts it.
    challenge_id: str = ""
    challenge_phrase: str = ""
    challenge_sent_at: datetime | None = None
    challenge_submitted_at: datetime | None = None
    challenge_approved_at: datetime | None = None
    # Which try the child is on. A rejection counts as an attempt, so the
    # phone can tell them "attempt 2 of 3" instead of starting over silently.
    challenge_attempt: int = 1
    block_requested_at: datetime | None = None
    extension_used_at: datetime | None = None
    extension_until: datetime | None = None
    # The latest notification dump the phone posted, already trimmed to what
    # the notifications sensor shows. None until the phone reports one.
    notifications: NotificationDump | None = None
    # A reward purchase is money-like: one portal submission may spend points
    # only once for the current blocked session.  Keep this durable so a Home
    # Assistant restart cannot turn a slow or failed unlock into a new charge.
    portal_reward_claims: set[str] = field(default_factory=set)
    extra: dict[str, str] = field(default_factory=dict)
    screen_monitor: ScreenMonitor | None = field(default=None, repr=False)
    _store: Store | None = field(default=None, repr=False)
    _portal_lock: asyncio.Lock = field(default_factory=asyncio.Lock, repr=False, compare=False)

    @property
    def signal(self) -> str:
        return SIGNAL_UPDATE.format(self.entry_id)

    def notify(self) -> None:
        async_dispatcher_send(self.hass, self.signal)

    @property
    def enforce_at(self) -> datetime | None:
        if not self.blocked:
            return None
        if self.block_requested_at is None:
            # No persisted grace start (e.g. old store or manual edit): do not
            # give a fresh 30s window after restart — enforce immediately.
            requested = dt_util.utcnow()
            # If an override/extension is already active, honour it instead.
            if self.extension_until is not None and self.extension_until > requested:
                return self.extension_until
            return requested
        requested = self.block_requested_at
        deadline = requested + timedelta(seconds=INITIAL_GRACE_SECONDS)
        if self.extension_until is not None and self.extension_until > deadline:
            deadline = self.extension_until
        return deadline

    @property
    def effective_blocked(self) -> bool:
        return self.blocked and self.enforce_at is not None and dt_util.utcnow() >= self.enforce_at

    @property
    def block_reason(self) -> str:
        """Why the block is, or is not, in force right now.

        One word a dashboard can show next to the switch: ``off`` when no
        parent asked for a block, ``grace_period`` while the agent's initial
        grace window is still running, ``extension`` while a save-work
        extension or a parent override is holding the block off, and
        ``enforced`` once the device is actually blocked.
        """
        if not self.blocked:
            return "off"
        if self.effective_blocked:
            return "enforced"
        if (
            self.extension_until is not None
            and self.extension_until > dt_util.utcnow()
        ):
            return "extension"
        return "grace_period"

    @property
    def extension_available(self) -> bool:
        return self.extension_used_at is None or dt_util.utcnow() >= (
            self.extension_used_at + timedelta(seconds=EXTENSION_COOLDOWN_SECONDS)
        )

    @property
    def extension_available_at(self) -> datetime | None:
        if self.extension_used_at is None:
            return None
        return self.extension_used_at + timedelta(seconds=EXTENSION_COOLDOWN_SECONDS)

    @property
    def challenge_state(self) -> str:
        """``none``, ``pending``, ``submitted`` or ``approved``."""
        if not self.challenge_id:
            return CHALLENGE_NONE
        if self.challenge_approved_at is not None:
            return CHALLENGE_APPROVED
        if self.challenge_submitted_at is not None:
            return CHALLENGE_SUBMITTED
        return CHALLENGE_PENDING

    @property
    def challenge_auto_approve_at(self) -> datetime | None:
        """When a submitted challenge approves itself, or None.

        Only a challenge that is waiting on a parent has a deadline; a pending
        one has not been sent yet, and an approved one is already decided.
        """
        if self.challenge_submitted_at is None or self.challenge_approved_at is not None:
            return None
        return self.challenge_submitted_at + timedelta(
            seconds=CHALLENGE_AUTO_APPROVE_SECONDS
        )

    @property
    def challenge_payload(self) -> dict | None:
        """The challenge the phone reads on its state poll, or None.

        None once a parent has approved it — that is how the phone knows the
        chore is done and it may unlock — and None when there is no challenge.
        """
        if not self.challenge_id or self.challenge_approved_at is not None:
            return None
        return {
            "id": self.challenge_id,
            "phrase": self.challenge_phrase,
            "sent_at": (
                self.challenge_sent_at.isoformat() if self.challenge_sent_at else None
            ),
            "status": (
                CHALLENGE_SUBMITTED
                if self.challenge_submitted_at is not None
                else CHALLENGE_PENDING
            ),
            "submitted_at": (
                self.challenge_submitted_at.isoformat()
                if self.challenge_submitted_at
                else None
            ),
            "auto_approve_at": (
                self.challenge_auto_approve_at.isoformat()
                if self.challenge_auto_approve_at
                else None
            ),
            "attempt": self.challenge_attempt,
        }

    def challenge_is_current(self, challenge_id: str) -> bool:
        """True when this id is the challenge the phone should be answering."""
        return bool(self.challenge_id) and challenge_id == self.challenge_id

    async def async_load(self) -> None:
        self._store = Store(self.hass, 1, f"{__package__}.{self.device_id}")
        data = await self._store.async_load() or {}
        self.block_requested_at = _parse_datetime(data.get("block_requested_at"))
        self.extension_used_at = _parse_datetime(data.get("extension_used_at"))
        self.extension_until = _parse_datetime(data.get("extension_until"))
        self.portal_reward_claims = {
            str(value) for value in data.get("portal_reward_claims", []) if str(value)
        }
        self.agent_stale = bool(data.get("agent_stale", False))
        self.challenge_id = str(data.get("challenge_id", ""))
        self.challenge_phrase = str(data.get("challenge_phrase", ""))
        self.challenge_sent_at = _parse_datetime(data.get("challenge_sent_at"))
        self.challenge_submitted_at = _parse_datetime(data.get("challenge_submitted_at"))
        self.challenge_approved_at = _parse_datetime(data.get("challenge_approved_at"))
        self.challenge_attempt = _attempt(data.get("challenge_attempt"))
        self.notifications = NotificationDump.from_dict(data.get("notifications"))
        self.location_request_id = str(data.get("location_request_id", ""))
        self.location_requested_at = _parse_datetime(data.get("location_requested_at"))

    async def async_save(self) -> None:
        if self._store is None:
            return
        await self._store.async_save(
            {
                "block_requested_at": _format_datetime(self.block_requested_at),
                "extension_used_at": _format_datetime(self.extension_used_at),
                "extension_until": _format_datetime(self.extension_until),
                "portal_reward_claims": sorted(self.portal_reward_claims),
                "agent_stale": self.agent_stale,
                "challenge_id": self.challenge_id,
                "challenge_phrase": self.challenge_phrase,
                "challenge_sent_at": _format_datetime(self.challenge_sent_at),
                "challenge_submitted_at": _format_datetime(self.challenge_submitted_at),
                "challenge_approved_at": _format_datetime(self.challenge_approved_at),
                "challenge_attempt": self.challenge_attempt,
                "notifications": (
                    self.notifications.as_dict() if self.notifications else None
                ),
                # The fix itself is not persisted; only the request, so a
                # restart does not lose a parent's ask.
                "location_request_id": self.location_request_id,
                "location_requested_at": _format_datetime(self.location_requested_at),
            }
        )

    async def async_set_blocked(self, blocked: bool) -> None:
        if blocked and (not self.blocked or self.block_requested_at is None):
            self.block_requested_at = dt_util.utcnow()
            self.extension_until = None
            self.portal_reward_claims.clear()
        elif not blocked:
            self.block_requested_at = None
            self.extension_until = None
            self.portal_reward_claims.clear()
        self.blocked = blocked
        await self.async_save()
        self.notify()

    async def async_reserve_portal_reward(self, reward_id: str) -> bool:
        """Atomically reserve one reward purchase for this blocked session."""
        async with self._portal_lock:
            if not self.effective_blocked or reward_id in self.portal_reward_claims:
                return False
            self.portal_reward_claims.add(reward_id)
            await self.async_save()
            self.notify()
            return True

    async def async_release_portal_reward(self, reward_id: str) -> None:
        """Release a reservation only when Home Assistant rejected the press."""
        if reward_id not in self.portal_reward_claims:
            return
        self.portal_reward_claims.remove(reward_id)
        await self.async_save()
        self.notify()

    async def async_request_extension(self) -> bool:
        if not self.blocked or not self.extension_available:
            return False
        now = dt_util.utcnow()
        self.extension_used_at = now
        self.extension_until = now + timedelta(seconds=EXTENSION_SECONDS)
        await self.async_save()
        self.notify()
        return True

    async def async_grant_parent_override(self) -> datetime | None:
        """Give one parent-PIN hour by reusing the agent-visible extension path.

        Agents already render ``extension_until`` as free-time banner mode with a
        countdown driven by ``enforce_at``, so an override needs no agent-side
        changes. ``extension_used_at`` is deliberately untouched: a parent grant
        neither consumes nor extends the child's own save-work extension.
        """
        if not self.blocked:
            return None
        self.extension_until = dt_util.utcnow() + timedelta(
            minutes=PARENT_OVERRIDE_MINUTES
        )
        await self.async_save()
        self.notify()
        return self.extension_until

    async def async_grant_points_hour(self) -> datetime | None:
        """Unlock one paid hour. Call only after the points deduction succeeded."""
        if not self.blocked:
            return None
        self.extension_until = dt_util.utcnow() + timedelta(minutes=POINTS_HOUR_MINUTES)
        await self.async_save()
        self.notify()
        return self.extension_until

    async def async_send_challenge(self, phrase: str) -> None:
        """Start a new challenge. The phone shows it on its next state poll."""
        self.challenge_id = new_request_id()
        self.challenge_phrase = phrase
        self.challenge_sent_at = dt_util.utcnow()
        self.challenge_submitted_at = None
        self.challenge_approved_at = None
        self.challenge_attempt = 1
        await self.async_save()
        self.notify()

    async def async_clear_challenge(self) -> None:
        """Cancel the challenge, approved or not."""
        self.challenge_id = ""
        self.challenge_phrase = ""
        self.challenge_sent_at = None
        self.challenge_submitted_at = None
        self.challenge_approved_at = None
        await self.async_save()
        self.notify()

    @property
    def location_request_payload(self) -> dict | None:
        """The pending location request the phone reads on its state poll.

        None once the request has expired, so a phone that was offline cannot
        answer a request a parent has stopped waiting for.
        """
        if not self.location_request_id:
            return None
        if not request_is_live(self.location_requested_at, dt_util.utcnow()):
            return None
        return {
            "id": self.location_request_id,
            "requested_at": (
                self.location_requested_at.isoformat()
                if self.location_requested_at
                else None
            ),
        }

    async def async_request_location(self) -> str:
        """Ask the phone for one fix. A second press replaces the request."""
        self.location_request_id = new_request_id()
        self.location_requested_at = dt_util.utcnow()
        await self.async_save()
        self.notify()
        return self.location_request_id

    async def async_expire_location_request(self) -> bool:
        """Drop a request nobody answered in time. True when one was dropped."""
        if not self.location_request_id:
            return False
        if request_is_live(self.location_requested_at, dt_util.utcnow()):
            return False
        self.location_request_id = ""
        await self.async_save()
        self.notify()
        return True

    async def async_store_location(self, payload: dict) -> None:
        """Store one answered fix and clear the request it answers."""
        coordinates = parse_coordinates(payload)
        if coordinates is None:
            return
        self.latitude, self.longitude = coordinates
        self.location_accuracy = parse_accuracy(payload)
        self.location_fix_age_seconds = parse_fix_age(payload)
        self.location_provider = parse_provider(payload)
        self.location_reported_at = dt_util.utcnow()
        self.location_request_id = ""
        await self.async_save()
        self.notify()

    async def async_store_notifications(self, dump: NotificationDump) -> bool:
        """Keep a dump for a day that is not older than the stored one.

        False when a newer day is already stored, so a late retry cannot
        overwrite it.
        """
        if self.notifications is not None and not is_newer(
            dump.date, self.notifications.date
        ):
            return False
        self.notifications = dump
        await self.async_save()
        self.notify()
        return True

    async def async_submit_challenge(self, challenge_id: str) -> bool:
        """Mark the current challenge sent. False when that id is not current.

        The child waits for a parent from here, so a retry after a dropped
        response is answered the same way and the first submission time is
        kept.
        """
        if not self.challenge_is_current(challenge_id):
            return False
        if self.challenge_approved_at is not None:
            return False
        if self.challenge_submitted_at is None:
            self.challenge_submitted_at = dt_util.utcnow()
            await self.async_save()
            self.notify()
        return True

    async def async_auto_approve_challenge(self) -> bool:
        """Approve a submitted challenge nobody decided on in time.

        Called whenever the challenge is read — the phone's state poll and the
        challenge sensor's tick — so no scheduler is needed. A parent's
        decision before the deadline wins: this only fires while the challenge
        is still waiting on one.
        """
        deadline = self.challenge_auto_approve_at
        if deadline is None or dt_util.utcnow() < deadline:
            return False
        return await self.async_approve_challenge()

    async def async_approve_challenge(self) -> bool:
        """Approve a submitted challenge. False when nothing is waiting."""
        if not self.challenge_id or self.challenge_submitted_at is None:
            return False
        if self.challenge_approved_at is None:
            self.challenge_approved_at = dt_util.utcnow()
            await self.async_save()
            self.notify()
        return True

    async def async_reject_challenge(self) -> bool:
        """Send a submitted challenge back to the child. False when nothing is waiting.

        The rejection counts as an attempt, so the child's next try is the next
        number rather than a silent restart.
        """
        if not self.challenge_id or self.challenge_submitted_at is None:
            return False
        self.challenge_submitted_at = None
        self.challenge_approved_at = None
        self.challenge_attempt += 1
        await self.async_save()
        self.notify()
        return True

    def _async_update_device_version(self) -> None:
        """Keep the device page's firmware version current.

        Home Assistant reads an entity's device info only when the entity is
        added, so a version that changed under a running Home Assistant has to
        be pushed to the registry as well.
        """
        registry = device_registry.async_get(self.hass)
        identifier = (DOMAIN, self.device_id)
        lookup = getattr(registry, "async_get_device_by_identifier", None)
        if lookup is not None:
            # Identifiers are scoped to a config entry on current versions.
            device = lookup(identifier, self.entry_id)
        else:  # Home Assistant before identifiers were scoped per entry.
            device = registry.async_get_device(identifiers={identifier})
        if device is None or device.sw_version == self.agent_version:
            return
        registry.async_update_device(device.id, sw_version=self.agent_version)

    def update_activity(self, payload: dict) -> None:
        self.last_seen = dt_util.utcnow()
        self.active_application = str(payload.get("application", ""))[:255]
        self.active_window = str(payload.get("window_title", ""))[:255]
        self.reported_username = str(payload.get("username", ""))[:128]
        previous_version = self.agent_version
        self.agent_version = str(payload.get("agent_version", ""))[:64]
        if self.agent_version != previous_version:
            self._async_update_device_version()
        if "installed_apps" in payload:
            self.installed_apps = parse_installed_apps(payload)
        previous_ui_status = self.ui_status
        previous_ui_error = self.ui_error
        self.ui_status = resolve_ui_status(previous_ui_status, payload)
        self.ui_error = resolve_ui_error(previous_ui_error, payload)
        self.ui_reported_at = self.last_seen
        if (
            self.ui_status != previous_ui_status
            and is_portal_error_status(self.ui_status)
            and (
                self.portal_error_notified_at is None
                or self.last_seen - self.portal_error_notified_at >= timedelta(minutes=15)
            )
        ):
            self.portal_error_notified_at = self.last_seen
            self.hass.bus.async_fire(
                "rowe_pc_blocker_portal_error",
                {
                    "device_id": self.device_id,
                    "device_name": self.device_name,
                    "status": self.ui_status,
                    "message": self.ui_error,
                },
            )
        if should_notify_enforcement_degraded(
            previous_ui_status,
            self.ui_status,
            block_requested=self.blocked,
            previous_block_requested=self.block_state_at_last_activity,
            last_notified_at=self.enforcement_degraded_notified_at,
            now=self.last_seen,
        ):
            self.enforcement_degraded_notified_at = self.last_seen
            self.hass.bus.async_fire(
                "rowe_pc_blocker_enforcement_degraded",
                {
                    "device_id": self.device_id,
                    "device_name": self.device_name,
                    "status": self.ui_status,
                    "message": self.ui_error,
                    "block_requested": self.blocked,
                },
            )
        self.block_state_at_last_activity = self.blocked
        self.notify()

    async def check_agent_stale(self) -> None:
        """Fire one stale event per offline episode, and one on recovery.

        An agent that has stopped polling cannot report its own silence, so the
        binary sensor calls this on its 30-second tick. A device that has never
        checked in is not stale: there is no episode to report. The flag is
        persisted, so a Home Assistant restart during an outage does not report
        the same episode a second time.
        """
        now = dt_util.utcnow()
        online = agent_is_online(self.last_seen, now, ONLINE_TIMEOUT_SECONDS)
        self.agent_stale, transition = agent_stale_transition(
            self.agent_stale, online, self.last_seen is not None
        )
        if transition is None:
            return
        await self.async_save()
        # Every entity re-reads its availability, so the telemetry entities go
        # unavailable the moment the agent crosses the timeout, and come back
        # when it checks in again.
        self.notify()
        if transition == AGENT_STALE:
            self.hass.bus.async_fire(
                EVENT_AGENT_STALE,
                {
                    "device_id": self.device_id,
                    "device_name": self.device_name,
                    "device_type": self.device_type,
                    "minutes_offline": int(
                        (now - self.last_seen).total_seconds() // 60
                    ),
                },
            )
        else:
            self.hass.bus.async_fire(
                EVENT_AGENT_RECOVERED,
                {
                    "device_id": self.device_id,
                    "device_name": self.device_name,
                    "device_type": self.device_type,
                },
            )


def new_request_id() -> str:
    """32 lowercase hex characters, safe to use in an entity attribute."""
    return secrets.token_hex(16)


def runtime_for_device(hass: HomeAssistant, device_id: str) -> PcRuntime | None:
    """The runtime for one configured device, or None when it is not set up."""
    return next(
        (
            item
            for item in hass.data.get(DOMAIN, {}).values()
            if getattr(item, "device_id", None) == device_id
        ),
        None,
    )


def _attempt(value) -> int:
    """A stored attempt number, never below the first try."""
    try:
        attempt = int(value)
    except (TypeError, ValueError):
        return 1
    return max(attempt, 1)


def _parse_datetime(value: str | None) -> datetime | None:
    return dt_util.parse_datetime(value) if value else None


def _format_datetime(value: datetime | None) -> str | None:
    return value.isoformat() if value else None
