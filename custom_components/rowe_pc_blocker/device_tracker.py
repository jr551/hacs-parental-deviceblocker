"""Map position for Android devices, from passively cached fixes only.

The agent never asks the GPS chip for a fix; it forwards positions other apps
already caused the system to calculate. So this entity is deliberately
best-effort: it stays unknown until something on the phone produces a fix, and
its `location_age_seconds` attribute says how stale the position is. Treat it as
"roughly where the phone was", never as live tracking.
"""

from homeassistant.components.device_tracker import SourceType, TrackerEntity

from .const import DEVICE_TYPE_ANDROID, DOMAIN
from .entity import ParentalDeviceEntity


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    # Windows PCs have no location source, so no entity is created for them.
    if getattr(runtime, "device_type", "") != DEVICE_TYPE_ANDROID:
        return
    async_add_entities([PassiveDeviceTracker(runtime)])


class PassiveDeviceTracker(ParentalDeviceEntity, TrackerEntity):
    _attr_name = "Passive location"
    _attr_icon = "mdi:map-marker-outline"

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        self._attr_unique_id = f"{runtime.device_id}_passive_location"

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
        return self.runtime.gps_accuracy or 0

    @property
    def extra_state_attributes(self) -> dict:
        return {
            "location_age_seconds": self.runtime.location_age_seconds,
            "location_provider": self.runtime.location_provider or None,
            "reported_at": (
                self.runtime.location_reported_at.isoformat()
                if self.runtime.location_reported_at
                else None
            ),
            "passive_only": True,
        }
