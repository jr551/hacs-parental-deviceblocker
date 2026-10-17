"""Temporary photo evidence for a chore challenge.

The phone uploads one JPEG per challenge, and a parent looks at it through the
image platform before deciding whether the chore was really done. Files live
under ``<config>/rowe_pc_blocker_evidence/<device_id>/<challenge_id>.jpg`` and
are deleted after 24 hours, so a photo cannot outlive the challenge it was
taken for. Nothing else in the integration reads them.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from pathlib import Path

from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

EVIDENCE_DIR = "rowe_pc_blocker_evidence"

# A photo is only useful while the challenge is fresh; after a day it is
# deleted whether or not a parent looked at it.
EVIDENCE_TTL_SECONDS = 24 * 60 * 60

# A phone camera JPEG is well under this; the cap is what keeps one upload from
# filling the config directory.
MAX_EVIDENCE_BYTES = 5 * 1024 * 1024

JPEG_CONTENT_TYPE = "image/jpeg"


def evidence_root(hass: HomeAssistant) -> Path:
    """The directory every device's evidence lives under."""
    return Path(hass.config.path(EVIDENCE_DIR))


def evidence_path(hass: HomeAssistant, device_id: str, challenge_id: str) -> Path:
    """Where one challenge's photo is stored.

    ``device_id`` is validated by the API and ``challenge_id`` is generated
    here, so neither can escape the evidence directory.
    """
    return evidence_root(hass) / device_id / f"{challenge_id}.jpg"


def evidence_file(
    hass: HomeAssistant, device_id: str, challenge_id: str
) -> Path | None:
    """The stored photo for one challenge, or None when there is none."""
    if not device_id or not challenge_id:
        return None
    path = evidence_path(hass, device_id, challenge_id)
    return path if path.is_file() else None


def evidence_expires_at(path: Path) -> datetime:
    """When a stored photo is deleted, from the moment it was written."""
    return dt_util.utc_from_timestamp(path.stat().st_mtime) + timedelta(
        seconds=EVIDENCE_TTL_SECONDS
    )


def evidence_refusal(content_type: str, size: int | None) -> tuple[int, dict] | None:
    """Why one upload cannot be stored, or None when it can.

    Called with the declared length before the body is read and with the real
    length after, so a lying Content-Length cannot get past the cap.
    """
    if content_type.split(";")[0].strip().lower() != JPEG_CONTENT_TYPE:
        return 415, {"error": "Send the photo as image/jpeg."}
    if size is not None and size > MAX_EVIDENCE_BYTES:
        return 413, {"error": "That photo is too large."}
    return None


async def async_save_evidence(
    hass: HomeAssistant, device_id: str, challenge_id: str, data: bytes
) -> datetime:
    """Store one photo, prune the expired ones, and say when this one goes."""
    path = evidence_path(hass, device_id, challenge_id)
    modified = await hass.async_add_executor_job(_write_evidence, path, data)
    await async_prune_evidence(hass)
    return dt_util.utc_from_timestamp(modified) + timedelta(
        seconds=EVIDENCE_TTL_SECONDS
    )


async def async_delete_evidence(
    hass: HomeAssistant, device_id: str, challenge_id: str
) -> None:
    """Drop one challenge's photo, if it was ever uploaded."""
    if not device_id or not challenge_id:
        return
    path = evidence_path(hass, device_id, challenge_id)
    await hass.async_add_executor_job(_delete_file, path)


async def async_prune_evidence(hass: HomeAssistant) -> int:
    """Delete every photo past its retention window. Returns how many went."""
    cutoff = dt_util.utcnow().timestamp() - EVIDENCE_TTL_SECONDS
    return await hass.async_add_executor_job(_prune, evidence_root(hass), cutoff)


def _write_evidence(path: Path, data: bytes) -> float:
    """Write one photo and return its modification time."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path.stat().st_mtime


def _delete_file(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        return
    try:
        path.parent.rmdir()
    except OSError:
        # Another challenge's photo is still there, or the directory is gone.
        pass


def _prune(root: Path, cutoff: float) -> int:
    """Delete photos older than the cutoff and any directory left empty."""
    if not root.is_dir():
        return 0
    removed = 0
    for device_dir in root.iterdir():
        if not device_dir.is_dir():
            continue
        for path in device_dir.glob("*.jpg"):
            try:
                if path.stat().st_mtime < cutoff:
                    path.unlink()
                    removed += 1
            except OSError:
                continue
        try:
            if not any(device_dir.iterdir()):
                device_dir.rmdir()
        except OSError:
            continue
    return removed
