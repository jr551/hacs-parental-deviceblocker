"""Create or reuse one Android phone entry from a chore-manager child name.

A parent types the child's name and the Home Assistant address on the phone.
The phone calls the provision endpoint and stores the device id and API key
itself. The parent never types the key.

A random internet client must not be able to mint keys. The name has to match
exactly one chore-manager child. If that phone's entry already has a
parent-override PIN, the same PIN is required before the existing key is
returned. If no PIN is set, the first provision for that known child is
allowed. An existing phone entry is reused and its key is not rotated.
"""

from __future__ import annotations

from dataclasses import dataclass
import hmac

ANDROID = "android"


@dataclass(frozen=True)
class PhoneEntry:
    """The fields provision needs from one integration entry."""

    device_id: str
    device_name: str
    device_type: str
    windows_username: str
    api_key: str
    parent_pin: str = ""


@dataclass(frozen=True)
class ProvisionDecision:
    """What the endpoint should do. ``api_key`` is set only when reusing one."""

    action: str
    device_id: str = ""
    child_name: str = ""
    api_key: str = ""
    message: str = ""
    pin_checked: bool = False


def _key(value: str) -> str:
    return "".join(character for character in (value or "").casefold() if character.isalnum())


def phone_device_id(child_name: str) -> str:
    """Casey -> casey-phone. Spaces and punctuation are dropped."""

    slug = _key(child_name or "")
    if not slug:
        return ""
    return f"{slug}-phone"


def match_child(child_name: str, children: list):
    """Match a typed name to exactly one chore-manager child."""

    wanted = _key(child_name or "")
    if not wanted:
        return None
    matches = [child for child in children if _key(child.name) == wanted]
    if len(matches) != 1:
        return None
    return matches[0]


def decide_provision(
    child_name: str,
    children: list,
    entries: list[PhoneEntry],
    supplied_pin: str,
) -> ProvisionDecision:
    """Choose reuse, create, or refusal. Does not generate a new key."""

    child = match_child(child_name, children)
    if child is None:
        return ProvisionDecision(
            "unknown_child",
            message="That name is not a child in Home Assistant.",
        )

    device_id = phone_device_id(child.name)
    exact = next((entry for entry in entries if entry.device_id == device_id), None)
    if exact is not None and exact.device_type != ANDROID:
        return ProvisionDecision(
            "unavailable",
            device_id=device_id,
            child_name=child.name,
            message="That name is already used by another device in Home Assistant.",
        )

    chosen = exact
    if chosen is None:
        child_key = _key(child.name)
        same_child = [
            entry
            for entry in entries
            if entry.device_type == ANDROID
            and _key(entry.windows_username) == child_key
        ]
        if len(same_child) == 1:
            chosen = same_child[0]
        elif len(same_child) > 1:
            return ProvisionDecision(
                "unavailable",
                child_name=child.name,
                message="That child already has more than one phone in Home Assistant.",
            )

    if chosen is None:
        return ProvisionDecision(
            "create",
            device_id=device_id,
            child_name=child.name,
        )

    pin = chosen.parent_pin.strip()
    supplied = (supplied_pin or "").strip()
    if pin:
        if not supplied:
            return ProvisionDecision(
                "pin_required",
                device_id=chosen.device_id,
                child_name=child.name,
                message="Enter the parent PIN for this child.",
                pin_checked=True,
            )
        if not hmac.compare_digest(supplied, pin):
            return ProvisionDecision(
                "bad_pin",
                device_id=chosen.device_id,
                child_name=child.name,
                message="That parent PIN is not right.",
                pin_checked=True,
            )

    return ProvisionDecision(
        "reuse",
        device_id=chosen.device_id,
        child_name=child.name,
        api_key=chosen.api_key,
        pin_checked=bool(pin),
    )
