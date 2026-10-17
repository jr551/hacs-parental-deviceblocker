"""Parent message and phone allowlist text entities."""

from homeassistant.components.text import TextEntity
from homeassistant.helpers.restore_state import RestoreEntity

from .apps import (
    DEFAULT_ALLOWED_APPS,
    MAX_ALLOWED_APPS_LENGTH,
    normalize_allowed_apps,
    parse_allowed_apps,
)
from .challenge import MAX_CHALLENGE_PHRASE_LENGTH
from .const import DEVICE_TYPE_ANDROID, DOMAIN
from .entity import ParentalDeviceEntity


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    entities = [PcMessageText(runtime)]
    # A PC has no app list to allow; the phone reads this one on its state poll.
    if runtime.device_type == DEVICE_TYPE_ANDROID:
        entities.append(AllowedAppsText(runtime))
        entities.append(NewChallengeText(runtime))
    async_add_entities(entities)


class PcMessageText(ParentalDeviceEntity, TextEntity, RestoreEntity):
    """The message the block screen shows the child.

    A parent control, so it stays available and settable while the device's
    agent is offline.
    """

    _attr_name = "Block message"
    _attr_icon = "mdi:message-alert"
    _attr_native_max = 255

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_block_message"

    @property
    def native_value(self) -> str:
        return self.runtime.message

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        old = await self.async_get_last_state()
        if old is not None and old.state not in ("unknown", "unavailable"):
            self.runtime.message = old.state

    async def async_set_value(self, value: str) -> None:
        self.runtime.message = value[:255]
        self.runtime.notify()


class AllowedAppsText(ParentalDeviceEntity, TextEntity, RestoreEntity):
    """The apps this phone may still open while blocked.

    Comma-separated Android package names. An empty list means the phone falls
    back to its built-in Calls/WhatsApp/Maps set, so clearing the field is a
    safe way to undo an edit. A parent control, so it stays available and
    settable while the phone's agent is offline.
    """

    _attr_name = "Allowed apps"
    _attr_icon = "mdi:apps"
    _attr_native_max = MAX_ALLOWED_APPS_LENGTH

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_allowed_apps"

    @property
    def native_value(self) -> str:
        # An unset list shows the built-in default so a parent can see and edit
        # what the phone will actually allow, rather than an empty box.
        return self.runtime.allowed_apps or ", ".join(DEFAULT_ALLOWED_APPS)

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        old = await self.async_get_last_state()
        if old is not None and old.state not in ("unknown", "unavailable"):
            # Restoring must never fail setup, so drop anything unreadable
            # rather than raising the way a parent's edit does.
            self.runtime.allowed_apps = ", ".join(parse_allowed_apps(old.state))

    async def async_set_value(self, value: str) -> None:
        # Raises ValueError for anything that is not a package name, so a typo
        # is refused instead of silently dropping an app the parent allowed.
        self.runtime.allowed_apps = normalize_allowed_apps(value)
        self.runtime.notify()



class NewChallengeText(ParentalDeviceEntity, TextEntity):
    """Start a chore challenge by typing its phrase.

    The same thing as calling ``rowe_pc_blocker.send_challenge``, but from the
    place a parent is already looking. The phone locks on its next state poll
    and stays locked until a parent accepts the submitted photo.

    The field always reads empty: it is a trigger, not a stored setting, and a
    value that lingered would read as a challenge that is still running.
    """

    _attr_name = "New challenge"
    _attr_icon = "mdi:lock-question"
    _attr_native_max = MAX_CHALLENGE_PHRASE_LENGTH

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_new_challenge"

    @property
    def native_value(self) -> str:
        return ""

    async def async_set_value(self, value: str) -> None:
        phrase = (value or "").strip()
        if not phrase:
            # Clearing the box is how the dashboard resets it; it is not a way
            # to cancel a challenge.
            return
        await self.runtime.async_send_challenge(phrase[:MAX_CHALLENGE_PHRASE_LENGTH])
