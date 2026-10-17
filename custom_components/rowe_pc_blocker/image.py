"""The photo a child uploaded for the current chore challenge.

Parent-facing: the image platform serves it through Home Assistant's own
authenticated image proxy, so the device key the phone uses is never involved.
The file itself is temporary; ``evidence.py`` owns where it lives and when it
is deleted.
"""

from __future__ import annotations

from datetime import datetime

from homeassistant.components.image import ImageEntity
from homeassistant.util import dt as dt_util

from .const import DEVICE_TYPE_ANDROID, DOMAIN
from .entity import ParentalDeviceEntity
from .evidence import JPEG_CONTENT_TYPE, evidence_file


async def async_setup_entry(hass, entry, async_add_entities) -> None:
    runtime = hass.data[DOMAIN][entry.entry_id]
    # Only a phone takes challenge photos.
    if runtime.device_type == DEVICE_TYPE_ANDROID:
        async_add_entities([ChallengeEvidenceImage(runtime)])


class ChallengeEvidenceImage(ParentalDeviceEntity, ImageEntity):
    """The stored photo for this device's current challenge, if any."""

    _attr_name = "Challenge evidence"
    _attr_content_type = JPEG_CONTENT_TYPE

    def __init__(self, runtime) -> None:
        super().__init__(runtime)
        # ImageEntity keeps the access tokens its image URL is built from.
        ImageEntity.__init__(self, runtime.hass)
        self._attr_unique_id = f"{runtime.device_id}_challenge_evidence"

    @property
    def image_last_updated(self) -> datetime | None:
        """When the photo was taken, or None when nothing is stored.

        Home Assistant re-reads the image whenever this changes, and shows no
        image at all while it is None.
        """
        path = self._evidence()
        if path is None:
            return None
        return dt_util.utc_from_timestamp(path.stat().st_mtime)

    async def async_image(self) -> bytes | None:
        path = self._evidence()
        if path is None:
            return None
        return await self.hass.async_add_executor_job(path.read_bytes)

    def _evidence(self):
        return evidence_file(
            self.hass, self.runtime.device_id, self.runtime.challenge_id
        )
