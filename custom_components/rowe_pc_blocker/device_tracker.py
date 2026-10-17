"""Where the phone was when a parent last asked.

The agent no longer reports position on every heartbeat: a parent presses
Request location, the request rides the phone's next state poll, and the phone
posts one fix back. So this entity is a stored answer rather than a live feed.
It stays unknown until the first fix arrives, and its `location_age_seconds`
attribute says how old the position is — the age is what a parent should read,
never the position on its own.
"""

from homeassistant.components.device_tracker import SourceType, TrackerEntity
from homeassistant.util import dt as dt_util

from .const import DEVICE_TYPE_ANDROID, DOMAIN
from .entity import ParentalDeviceEntity


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    # Windows PCs have no location source, so no entity is created for them.
    if getattr(runtime, "device_type", "") != DEVICE_TYPE_ANDROID:
        return
    async_add_entities([LocationTracker(runtime)])


class LocationTracker(ParentalDeviceEntity, TrackerEntity):
    """The last fix the phone answered with, and how old it is."""

    _attr_name = "Location"
    _attr_icon = "mdi:map-marker-outline"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_location"

    @property
    def source_type(self) -> SourceType:
        return SourceType.GPS

    @property
    def latitude(self) -> float | None:
        return self.runtime.latitude

    @property
    def longitude(self) -> float | None:
        return self.runtime.longitude

    @property
    def location_accuracy(self) -> int:
        # Home Assistant treats 0 as "unknown accuracy".
        accuracy = self.runtime.location_accuracy
        return int(accuracy) if accuracy else 0

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "location_age_seconds": self._age_seconds(),
            "location_provider": self.runtime.location_provider or None,
            "reported_at": (
                self.runtime.location_reported_at.isoformat()
                if self.runtime.location_reported_at
                else None
            ),
            "requested_at": (
                self.runtime.location_requested_at.isoformat()
                if self.runtime.location_requested_at
                else None
            ),
            "request_pending": self._request_pending(),
            "request_age_seconds": self._request_age_seconds(),
        }

    def _request_pending(self) -> bool:
        """True while a parent's request is still waiting on the phone.

        A phone whose screen is off can leave the request unanswered, and the
        tracker used to stay ``unknown`` with nothing to explain it. Saying the
        request is still pending tells a parent to try again with the phone in
        hand rather than assuming the button is broken.
        """
        requested_at = self.runtime.location_requested_at
        if not self.runtime.location_request_id or requested_at is None:
            return False
        answered_at = self.runtime.location_reported_at
        return answered_at is None or answered_at < requested_at

    def _request_age_seconds(self) -> int | None:
        if not self._request_pending():
            return None
        requested_at = self.runtime.location_requested_at
        return max(0, int((dt_util.utcnow() - requested_at).total_seconds()))

    def _age_seconds(self) -> int | None:
        """How old the stored position is, not how old the answer is.

        The phone reports how stale its fix already was; the time since the
        answer arrived is added, so the number keeps growing while nothing new
        is asked for.
        """
        reported_at = self.runtime.location_reported_at
        if reported_at is None:
            return None
        fix_age = self.runtime.location_fix_age_seconds or 0
        return fix_age + int((dt_util.utcnow() - reported_at).total_seconds())
