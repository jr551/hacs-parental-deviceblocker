"""Pin the agent HTTP contract without a full Home Assistant runtime."""

from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INTEGRATION = ROOT / "custom_components" / "rowe_pc_blocker"
FIXTURES = ROOT / "tests" / "fixtures" / "protocol"


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
APPS = _load_module("rowe_pc_blocker_apps_for_protocol", INTEGRATION / "apps.py")
PROVISION = _load_module(
    "rowe_pc_blocker_provision_for_protocol", INTEGRATION / "provision.py"
)

POST_VIEWS = frozenset(
    {
        "PcActivityView",
        "PcExtensionView",
        "PcUnblockRequestView",
        "PcPortalActionView",
        "PcParentOverrideView",
        "PcPointsHourView",
        "AndroidProvisionView",
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


def _fixture(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class ProtocolSurfaceTests(unittest.TestCase):
    def test_documented_routes_match_views(self) -> None:
        documented = {(item["method"], item["path"]) for item in PROTOCOL.API_ROUTES}
        self.assertEqual(documented, _routes_from_api_source())

    def test_state_response_fixture_matches_the_documented_keys(self) -> None:
        self.assertEqual(set(_fixture("state_response.json")), PROTOCOL.STATE_RESPONSE_KEYS)

    def test_activity_request_fixture_matches_the_documented_keys(self) -> None:
        self.assertEqual(
            set(_fixture("activity_request.json")), PROTOCOL.ACTIVITY_REQUEST_KEYS
        )

    def test_the_installed_app_report_is_the_documented_shape(self) -> None:
        apps = APPS.parse_installed_apps(
            {"installed_apps": [{"package": "com.whatsapp", "label": "WhatsApp"}]}
        )
        self.assertEqual(set(apps[0]), PROTOCOL.INSTALLED_APP_KEYS)

    def test_the_provision_shapes_are_the_documented_ones(self) -> None:
        request = PROVISION.PendingRequest(
            request_id="a" * 32,
            child_name="Casey",
            requested_at=datetime(2026, 10, 17, tzinfo=timezone.utc),
        )
        status_code, body = PROVISION.request_response(request)
        self.assertEqual(status_code, 202)
        self.assertEqual(set(body), PROTOCOL.PROVISION_PENDING_KEYS)

        request.status = PROVISION.STATUS_APPROVED
        request.device_id = "casey-phone"
        request.api_key = "the-key"
        status_code, body = PROVISION.collection_response(request)
        self.assertEqual(status_code, 200)
        self.assertEqual(set(body), PROTOCOL.PROVISION_APPROVED_KEYS)

    def test_the_unblock_request_fixture_matches_the_documented_keys(self) -> None:
        self.assertEqual(
            set(_fixture("unblock_request.json")), PROTOCOL.UNBLOCK_REQUEST_KEYS
        )

    def test_the_unblock_pending_body_is_the_documented_shape(self) -> None:
        request = PROVISION.UnblockRequest(
            request_id="b" * 32,
            device_id="casey-phone",
            package="com.roblox.client",
            label="Roblox",
            requested_at=datetime(2026, 10, 17, tzinfo=timezone.utc),
        )
        body = PROVISION.unblock_pending_body(request)
        self.assertEqual(set(body), PROTOCOL.UNBLOCK_PENDING_KEYS)
        self.assertEqual(body["request_id"], "b" * 32)
        self.assertEqual(body["status"], PROVISION.STATUS_PENDING)


if __name__ == "__main__":
    unittest.main()
