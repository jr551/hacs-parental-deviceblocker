# Agent wire protocol

This document describes the HTTP API between **Parental Device Blocker** Home Assistant integration and installed agents (Windows `RowePcBlocker` / `RowePcActivity`, Android `lol.rowe.blocker`). Paths and JSON field names are compatibility contracts: clients in the field depend on them.

Base URL: `{HomeAssistantUrl}` (no trailing slash required on the client; paths are rooted at `/api/rowe_pc_blocker/`).

## Authentication

All endpoints use `requires_auth = false` on the Home Assistant view and instead validate a per-device secret.

| Mechanism | Where |
|-----------|--------|
| Header `X-Device-Blocker-Key` | Preferred on all requests |
| Header `X-Rowe-Key` | Legacy alias, same value |
| Query `key=` | Only `GET …/portal` (kiosk WebView initial load; also sets a short-lived HttpOnly session cookie) |

Wrong or missing credentials → **401 Unauthorized** (empty body).

## Windows polling and activity

The Windows blocker service polls state on a configurable interval (default ≥ 5 seconds). The activity companion posts on foreground changes, at least every 60 seconds, or when UI telemetry changes.

### `GET /api/rowe_pc_blocker/{device_id}/state`

**Response 200** JSON object (keys and types):

| Field | Type | Notes |
|-------|------|--------|
| `device_id` | string | Must match path |
| `blocked` | boolean | Effective block (after grace/extension) |
| `block_requested` | boolean | Parent-requested block |
| `message` | string | Shown on overlay |
| `windows_username` | string | Local account label |
| `device_type` | string | `windows` or `android` |
| `enforce_at` | string \| null | ISO-8601 when hard block starts |
| `extension_available` | boolean | Save-work extension allowed now |
| `extension_available_at` | string \| null | ISO-8601 |
| `extension_until` | string \| null | ISO-8601 end of active extension |

### `POST /api/rowe_pc_blocker/{device_id}/activity`

**Request** JSON (subset used by Windows agent):

```json
{
  "application": "chrome.exe",
  "window_title": "Example",
  "username": "child",
  "agent_version": "0.9.35",
  "ui_status": "idle",
  "ui_error": ""
}
```

**Response 200:** `{"ok": true}`

### `POST /api/rowe_pc_blocker/{device_id}/extension`

**Request:** `{}` (empty object)

**Response 200** when granted, **429** when rate-limited:

```json
{
  "granted": true,
  "extension_until": "2026-01-01T12:05:00+00:00",
  "extension_available_at": "2026-01-01T13:00:00+00:00"
}
```

## Points portal (Windows)

### `GET /api/rowe_pc_blocker/{device_id}/portal`

Returns HTML kiosk page. Query `key` allowed for auth.

### `GET /api/rowe_pc_blocker/{device_id}/portal/data`

**Response 200** portal snapshot JSON, or **404** if child mapping missing.

### `POST /api/rowe_pc_blocker/{device_id}/portal/action`

**Request:** `{"type": "claim_reward"|"complete_chore", "id": "<item id>"}`

**Response 200:** `{"ok": true}` on success; **400** / **404** / **409** with `{"error": "…"}`.

## Parent override

### `POST /api/rowe_pc_blocker/{device_id}/parent_override`

**Request:** `{"pin": "<parent pin>"}`

**Response 200:** `{"ok": true, "minutes": 60, "until": "<iso>"}` when blocked and PIN correct.

**503** if PIN not configured; **403** / **429** on wrong PIN or lockout; **409** if not blocked.

## Android media backup (legacy endpoints)

Server-side S3 backup configuration was removed in integration **0.10.0**. Endpoints remain so older Android builds fail gracefully:

### `GET /api/rowe_pc_blocker/{device_id}/backup/config`

**Response 200** (backup always disabled):

```json
{
  "enabled": false,
  "initial_sync_wifi_only": true,
  "requires_external_power": true,
  "max_file_bytes": 21474836480,
  "configuration_id": ""
}
```

### `POST /api/rowe_pc_blocker/{device_id}/backup/presign`

**Response 503:** `{"error": "Media backup is disabled."}`

### `POST /api/rowe_pc_blocker/{device_id}/backup/status`

**Request** progress object (`status`, `uploaded`, `skipped`, `initial_complete`, `last_success`, `error`).

**Response 200:** `{"ok": true}` (accepted but not surfaced in HA after 0.10.0).

## Contract tests

Machine-readable pins live in `custom_components/rowe_pc_blocker/protocol_surface.py` and `tests/test_protocol.py`.
