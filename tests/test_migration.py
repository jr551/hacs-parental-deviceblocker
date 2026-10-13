"""Config-entry migration helpers."""

import importlib.util
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONST_PATH = ROOT / "custom_components" / "rowe_pc_blocker" / "const.py"
SPEC = importlib.util.spec_from_file_location("rowe_const", CONST_PATH)
CONST = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = CONST
SPEC.loader.exec_module(CONST)


class MigrationTests(unittest.TestCase):
    def test_legacy_s3_keys_are_stripped_from_options(self) -> None:
        options = {
            "parent_pin": "1234",
            "media_backup_enabled": True,
            "s3_secret_key": "secret",
            "screen_monitor_enabled": False,
        }
        cleaned = {
            k: v for k, v in options.items() if k not in CONST.LEGACY_S3_OPTION_KEYS
        }
        self.assertEqual(
            cleaned,
            {"parent_pin": "1234", "screen_monitor_enabled": False},
        )


if __name__ == "__main__":
    unittest.main()
