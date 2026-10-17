"""Config-entry upgrade rules.

Kept free of Home Assistant imports so the rules can be tested without a
running Home Assistant instance.
"""

from __future__ import annotations

from .const import OPTION_KEYS

# Version 2 dropped the S3 media backup options. The entry version records that
# the filter below has already run, so an upgrade happens exactly once.
ENTRY_VERSION = 2


def migrate_options(options: dict) -> dict:
    """Keep only the options the current version still reads.

    Options written by removed features are dropped rather than carried
    forward, so an upgraded entry cannot keep handing a stale setting to code
    that no longer exists.
    """
    return {key: value for key, value in options.items() if key in OPTION_KEYS}
