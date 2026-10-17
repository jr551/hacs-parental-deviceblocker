"""Config-entry upgrade rules.

``migration.py`` imports its sibling ``const``, so it is loaded through the
package helper in ``ha_stub.py``.
"""

from __future__ import annotations

import unittest

import ha_stub

CONST = ha_stub.load("const")
MIGRATION = ha_stub.load("migration")

# Options written by the S3 media backup feature, removed in 0.10.0.
REMOVED_S3_OPTIONS = (
    "media_backup_enabled",
    "s3_endpoint",
    "s3_region",
    "s3_bucket",
    "s3_access_key",
    "s3_secret_key",
    "s3_prefix",
    "clear_s3_credentials",
)


class MigrationTests(unittest.TestCase):
    def test_removed_feature_options_are_dropped_on_upgrade(self) -> None:
        options = {
            "parent_pin": "1234",
            "media_backup_enabled": True,
            "s3_secret_key": "secret",
            "screen_monitor_enabled": False,
        }
        self.assertEqual(
            MIGRATION.migrate_options(options),
            {"parent_pin": "1234", "screen_monitor_enabled": False},
        )

    def test_no_removed_feature_option_is_still_read(self) -> None:
        for key in REMOVED_S3_OPTIONS:
            self.assertNotIn(key, CONST.OPTION_KEYS)

    def test_migrating_twice_changes_nothing(self) -> None:
        options = {"parent_pin": "1234", "s3_bucket": "old"}
        once = MIGRATION.migrate_options(options)
        self.assertEqual(MIGRATION.migrate_options(once), once)

    def test_an_entry_without_options_survives(self) -> None:
        self.assertEqual(MIGRATION.migrate_options({}), {})


if __name__ == "__main__":
    unittest.main()
