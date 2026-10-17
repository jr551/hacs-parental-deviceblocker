"""Stable agent API surface (paths, methods, response keys) for contract tests."""

from __future__ import annotations

AUTH_HEADERS = ("X-Device-Blocker-Key", "X-Rowe-Key")
AUTH_QUERY_PARAM = "key"  # portal HTML only

API_ROUTES: tuple[dict[str, str | bool], ...] = (
    {"method": "GET", "path": "/api/rowe_pc_blocker/{device_id}/state"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/activity"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/notifications"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/extension"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/unblock_request"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/challenge/accept"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/challenge/evidence"},
    {"method": "GET", "path": "/api/rowe_pc_blocker/{device_id}/portal", "query_key_ok": True},
    {"method": "GET", "path": "/api/rowe_pc_blocker/{device_id}/portal/data"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/portal/action"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/points_hour"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/provision"},
    {"method": "GET", "path": "/api/rowe_pc_blocker/provision/{request_id}"},
    {"method": "POST", "path": "/api/rowe_pc_blocker/{device_id}/parent_override"},
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
        "allowed_apps",
        "challenge",
        "family",
    }
)

FAMILY_KEYS = frozenset({"phone", "pc"})

FAMILY_PEER_KEYS = frozenset(
    {
        "device_id",
        "device_type",
        "blocked",
        "message",
        "agent_online",
        "last_seen",
        "active_application",
        "challenge",
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
        "installed_apps",
    }
)

INSTALLED_APP_KEYS = frozenset({"package", "label"})

NOTIFICATION_REQUEST_KEYS = frozenset({"date", "count", "truncated", "items"})

NOTIFICATION_ITEM_KEYS = frozenset({"package", "app", "title", "text", "posted_at"})

EXTENSION_RESPONSE_KEYS = frozenset(
    {"granted", "extension_until", "extension_available_at"}
)

PROVISION_REQUEST_KEYS = frozenset({"child_name"})

PROVISION_PENDING_KEYS = frozenset({"status", "request_id", "child_name"})

PROVISION_APPROVED_KEYS = frozenset({"status", "device_id", "api_key", "child_name"})

UNBLOCK_REQUEST_KEYS = frozenset({"package", "label"})

UNBLOCK_PENDING_KEYS = frozenset({"ok", "request_id", "status"})

CHALLENGE_REQUEST_KEYS = frozenset({"id"})

CHALLENGE_PAYLOAD_KEYS = frozenset({"id", "phrase", "sent_at"})

CHALLENGE_EVIDENCE_KEYS = frozenset({"ok", "expires_at"})

# The success body of every write endpoint that has nothing else to say.
OK_BODY = {"ok": True}

PARENT_OVERRIDE_REQUEST_KEYS = frozenset({"pin"})

PORTAL_ACTION_REQUEST_KEYS = frozenset({"type", "id"})
