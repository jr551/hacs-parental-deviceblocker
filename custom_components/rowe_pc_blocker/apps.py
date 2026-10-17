"""App-list helpers shared with the phone agent.

The parent edits one comma-separated list of Android package names in Home
Assistant; the phone reads the same list from its state poll. Installed apps
travel the other way, on the activity heartbeat, so the parent can see what the
phone offers before choosing.

This module has no Home Assistant imports so the rules can be tested without a
running Home Assistant instance.
"""

from __future__ import annotations

import re

# Android package names: dot-separated segments, each starting with a letter.
PACKAGE_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*")

# A text entity state is capped at 255 characters, so the list has to fit.
MAX_ALLOWED_APPS_LENGTH = 255

# The apps a blocked phone may open by default, matching the phone's own
# built-in Calls/WhatsApp/Maps/MapMyWalk/Classroom/Clock/Camera shortcuts. The
# phone keeps extra fallbacks for other dialers, clocks and cameras, but this
# is the list a parent sees and edits, so it has to fit the 255-character cap.
DEFAULT_ALLOWED_APPS = (
    "com.whatsapp",
    "com.google.android.apps.maps",
    "com.google.android.apps.classroom",
    "com.mapmywalk.android2",
    "com.samsung.android.dialer",
    "com.sec.android.app.clockpackage",
    "com.sec.android.app.camera",
)

# Bound what one heartbeat may store; a phone with more apps than this is
# reporting something other than its launcher list.
MAX_INSTALLED_APPS = 500
MAX_APP_FIELD_LENGTH = 128


def parse_allowed_apps(value: str) -> list[str]:
    """Split a stored list into package names, ignoring blanks and duplicates."""

    packages: list[str] = []
    for part in str(value or "").split(","):
        package = part.strip()
        if not package or package in packages:
            continue
        if PACKAGE_PATTERN.fullmatch(package) is None:
            continue
        packages.append(package)
    return packages


def normalize_allowed_apps(value: str) -> str:
    """Validate and canonicalise a comma-separated list typed by a parent.

    Raises ValueError for anything that is not an Android package name, so the
    text entity rejects the write instead of silently dropping an app.
    """

    packages: list[str] = []
    for part in str(value or "").split(","):
        package = part.strip()
        if not package:
            continue
        if PACKAGE_PATTERN.fullmatch(package) is None:
            raise ValueError(f"Not an Android package name: {package}")
        if package not in packages:
            packages.append(package)
    normalized = ", ".join(packages)
    if len(normalized) > MAX_ALLOWED_APPS_LENGTH:
        raise ValueError(
            f"Keep the list to {MAX_ALLOWED_APPS_LENGTH} characters or fewer."
        )
    return normalized


def append_allowed_app(allowed_apps: str, package: str) -> str:
    """The parent's list with one package added, keeping the built-in defaults.

    An empty list means the phone falls back to ``DEFAULT_ALLOWED_APPS``, so
    adding an app to an empty list has to spell those defaults out or the
    approval would drop them. Raises ValueError when the result would not fit
    the text entity's cap, so a parent can shorten the list and try again.
    """

    packages = parse_allowed_apps(allowed_apps) or list(DEFAULT_ALLOWED_APPS)
    if package not in packages:
        packages.append(package)
    return normalize_allowed_apps(", ".join(packages))


def parse_installed_apps(payload: dict) -> list[dict]:
    """Bound and clean the installed-app list reported by a phone."""

    raw = payload.get("installed_apps")
    if not isinstance(raw, list):
        return []
    apps: list[dict] = []
    for item in raw[:MAX_INSTALLED_APPS]:
        if not isinstance(item, dict):
            continue
        package = str(item.get("package", "")).strip()[:MAX_APP_FIELD_LENGTH]
        if not package:
            continue
        label = str(item.get("label", "")).strip()[:MAX_APP_FIELD_LENGTH]
        apps.append({"package": package, "label": label})
    return apps
