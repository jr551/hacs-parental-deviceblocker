"""Pin the agent HTTP contract without a full Home Assistant runtime."""

from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INTEGRATION = ROOT / "custom_components" / "rowe_pc_blocker"


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


PROTOCOL = _load_module(
    "rowe_pc_blocker_protocol_surface", INTEGRATION / "protocol_surface.py"
)

POST_VIEWS = frozenset(
    {
        "PcActivityView",
        "PcExtensionView",
        "PcPortalActionView",
        "PcParentOverrideView",
        "PcPointsHourView",
        "AndroidProvisionView",
        "AndroidBackupPresignView",
        "AndroidBackupStatusView",
    }
)


def _routes_from_api_source() -> set[tuple[str, str]]:
    text = (INTEGRATION / "api.py").read_text(encoding="utf-8")
    routes: set[tuple[str, str]] = set()
    current_class = ""
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("class ") and stripped.endswith("(HomeAssistantView):"):
            current_class = stripped.split()[1].split("(")[0]
        if stripped.startswith('url = "') and current_class:
            path = stripped.split('"')[1]
            method = "POST" if current_class in POST_VIEWS else "GET"
            routes.add((method, path))
    return routes


class ProtocolSurfaceTests(unittest.TestCase):
    def test_documented_routes_match_views(self) -> None:
        documented = {(item["method"], item["path"]) for item in PROTOCOL.API_ROUTES}
        self.assertEqual(documented, _routes_from_api_source())

    def test_state_response_keys_fixture(self) -> None:
        fixture = {
            "device_id": "child-pc",
            "blocked": False,
            "block_requested": False,
            "message": "Paused",
            "windows_username": "child",
            "device_type": "windows",
            "enforce_at": None,
            "extension_available": True,
            "extension_available_at": None,
            "extension_until": None,
        }
        self.assertEqual(set(fixture.keys()), PROTOCOL.STATE_RESPONSE_KEYS)

    def test_activity_request_fixture(self) -> None:
        fixture = {
            "application": "Idle",
            "window_title": "",
            "username": "child",
            "agent_version": "0.9.35",
            "ui_status": "idle",
            "ui_error": "",
        }
        self.assertEqual(set(fixture.keys()), PROTOCOL.ACTIVITY_REQUEST_KEYS)

    def test_backup_disabled_shapes(self) -> None:
        self.assertEqual(
            PROTOCOL.BACKUP_CONFIG_DISABLED_BODY,
            {
                "enabled": False,
                "initial_sync_wifi_only": True,
                "requires_external_power": True,
                "max_file_bytes": PROTOCOL.MAX_BACKUP_BYTES,
                "configuration_id": "",
            },
        )
        self.assertEqual(
            set(PROTOCOL.BACKUP_CONFIG_DISABLED_BODY.keys()),
            PROTOCOL.BACKUP_CONFIG_RESPONSE_KEYS,
        )
        self.assertEqual(PROTOCOL.BACKUP_PRESIGN_DISABLED_STATUS, 503)
        self.assertEqual(
            PROTOCOL.BACKUP_PRESIGN_DISABLED_BODY,
            {"error": "Media backup is disabled."},
        )

    def test_fixture_files_are_stable(self) -> None:
        fixtures_dir = ROOT / "tests" / "fixtures" / "protocol"
        for name in (
            "state_response.json",
            "activity_request.json",
            "backup_config_disabled.json",
            "backup_presign_disabled.json",
        ):
            path = fixtures_dir / name
            self.assertTrue(path.is_file(), f"missing {path}")
            json.loads(path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
