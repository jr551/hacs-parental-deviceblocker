"""Stable agent API surface (paths, methods, response keys) for contract tests."""

from __future__ import annotations

# Android backup/config still returns these limits when backup is disabled.
MAX_BACKUP_BYTES = 20 * 1024 * 1024 * 1024

AUTH_HEADERS = ("X-Device-Blocker-Key", "X-Rowe-Key")
AUTH_QUERY_PARAM = "key"  # portal HTML only

API_ROUTES: tuple[dict[str, str | bool], ...] = (
    {"method": "GET", "path": "/api/rowe_pc_blocker/{device_id}/state"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/activity"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/extension"},
    {"method": "GET", "path": "/api/rowe_pc_blocker/{device_id}/portal", "query_key_ok": True},
    {"method": "GET", "path": "/api/rowe_pc_blocker/{device_id}/portal/data"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/portal/action"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/parent_override"},
    {"method": "GET", "path": "/api/rowe_pc_blocker/{device_id}/backup/config"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/backup/presign"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/backup/status"},
)

STATE_RESPONSE_KEYS = frozenset(
    {
        "device_id",
        "blocked",
        "block_requested",
        "message",
        "windows_username",
        "device_type",
        "enforce_at",
        "extension_available",
        "extension_available_at",
        "extension_until",
    }
)

ACTIVITY_REQUEST_KEYS = frozenset(
    {
        "application",
        "window_title",
        "username",
        "agent_version",
        "ui_status",
        "ui_error",
    }
)

EXTENSION_RESPONSE_KEYS = frozenset(
    {"granted", "extension_until", "extension_available_at"}
)

BACKUP_CONFIG_RESPONSE_KEYS = frozenset(
    {
        "enabled",
        "initial_sync_wifi_only",
        "requires_external_power",
        "max_file_bytes",
        "configuration_id",
    }
)

BACKUP_CONFIG_DISABLED_BODY = {
    "enabled": False,
    "initial_sync_wifi_only": True,
    "requires_external_power": True,
    "max_file_bytes": MAX_BACKUP_BYTES,
    "configuration_id": "",
}

BACKUP_PRESIGN_DISABLED_STATUS = 503
BACKUP_PRESIGN_DISABLED_BODY = {"error": "Media backup is disabled."}

BACKUP_STATUS_REQUEST_KEYS = frozenset(
    {"status", "uploaded", "skipped", "initial_complete", "last_success", "error"}
)

BACKUP_STATUS_OK_BODY = {"ok": True}

ACTIVITY_OK_BODY = {"ok": True}

PARENT_OVERRIDE_REQUEST_KEYS = frozenset({"pin"})

PORTAL_ACTION_REQUEST_KEYS = frozenset({"type", "id"})
