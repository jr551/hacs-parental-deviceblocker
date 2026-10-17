"""The other devices belonging to the same child.

A child may have a phone and a PC. Each device's state poll carries the other
one's status so the agent can show "Phone: open — com.whatsapp" without a
second Home Assistant round trip. Peers are matched on the chore-manager child
name, which both entries store as ``windows_username``.

This module has no Home Assistant imports so the rules can be tested without a
running Home Assistant instance.
"""

from __future__ import annotations

from datetime import datetime

from .agent_status import agent_is_online
from .provision import ANDROID, child_key

PHONE = "phone"
PC = "pc"


def peer_payload(runtime, now: datetime, timeout_seconds: int) -> dict:
    """One peer's status as the agent sees it."""

    return {
        "device_id": runtime.device_id,
        "device_type": runtime.device_type,
        "blocked": runtime.effective_blocked,
        "message": runtime.message,
        "agent_online": agent_is_online(runtime.last_seen, now, timeout_seconds),
        "last_seen": runtime.last_seen.isoformat() if runtime.last_seen else None,
        "active_application": runtime.active_application,
    }


def family_peers(runtime, runtimes, now: datetime, timeout_seconds: int) -> dict:
    """Return this child's other devices as ``{"phone": peer|None, "pc": peer|None}``.

    ``runtimes`` is every configured device; the requesting device is skipped.
    A child with two phones (or two PCs) reports the first by device id.
    """

    peers: dict[str, dict | None] = {PHONE: None, PC: None}
    child = child_key(runtime.windows_username)
    if not child:
        return peers
    others = sorted(
        (
            other
            for other in runtimes
            if other is not runtime and child_key(other.windows_username) == child
        ),
        key=lambda other: other.device_id,
    )
    for other in others:
        slot = PHONE if other.device_type == ANDROID else PC
        if peers[slot] is None:
            peers[slot] = peer_payload(other, now, timeout_seconds)
    return peers
