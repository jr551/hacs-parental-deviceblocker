# Agent wire protocol

This document describes the HTTP API between **Parental Device Blocker** Home Assistant integration and installed agents (Windows `RowePcBlocker` / `RowePcActivity`, Android `lol.rowe.blocker`). Paths and JSON field names are compatibility contracts: clients in the field depend on them. The `rowe_*` prefix is a **legacy technical name** kept for backward compatibility with deployed agents—not the product brand.

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
| `points_hour_cost` | integer | Points one bought hour costs |
| `points_balance` | integer \| null | Child's Taskmate balance, or null |
| `wake_alarm` | object \| null | Alarm schedule; null leaves the agent's cache alone |
| `allowed_apps` | string[] | Android allow-list; empty means "use the agent's built-in defaults" |
| `family` | object | This child's other devices: `{"phone": peer\|null, "pc": peer\|null}` |

A `family` peer is `{"device_id", "device_type", "blocked", "message", "agent_online", "last_seen", "active_application"}`, where `last_seen` is ISO-8601 or null. Peers are the same child's other devices, matched on the chore-manager child name; the requesting device is never its own peer.

Reading this endpoint refreshes the device's `last_seen`, so an agent may use it as a plain liveness check-in.

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

The Android agent adds optional fields to the same body:

| Field | Type | Notes |
|-------|------|-------|
| `latitude`, `longitude` | number | Cached position, when one is available |
| `gps_accuracy` | integer | Metres |
| `location_age_seconds` | integer | Age of the cached fix |
| `location_provider` | string | `network` or `gps` |
| `installed_apps` | array | `[{"package", "label"}]`; sent only when the set changed |

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

## Android setup (provisioning)

A phone asks to be set up from a child name and then waits for a parent to approve the request in Home Assistant. Only an approved request hands the phone a device id and API key, so the parent never types a key. Pending requests live in `.storage/rowe_pc_blocker.pending` and expire after **30 minutes**. Collection is idempotent: an approved request keeps returning the same credentials until it expires.

These two paths take no device credentials — the phone has none yet.

### `POST /api/rowe_pc_blocker/provision`

**Request:** `{"child_name": "Casey"}`

**Response 202** — a parent has not decided yet:

```json
{
  "status": "pending",
  "request_id": "0123456789abcdef0123456789abcdef",
  "child_name": "Casey"
}
```

`request_id` is 32 lowercase hex characters.

**Response 200** — this child's phone was already approved (the credentials were never collected):

```json
{
  "status": "approved",
  "device_id": "casey-phone",
  "api_key": "…",
  "child_name": "Casey"
}
```

**Response 404:** `{"error": "That name is not a child in Home Assistant."}`

### `GET /api/rowe_pc_blocker/provision/{request_id}`

**Response 200** — still waiting: `{"status": "pending"}`

**Response 200** — approved:

```json
{
  "status": "approved",
  "device_id": "casey-phone",
  "api_key": "…",
  "child_name": "Casey"
}
```

**Response 403:** `{"status": "rejected", "error": "A parent declined this setup."}`

**Response 404:** `{"error": "That setup request has expired."}`

## Contract tests

Machine-readable pins live in `custom_components/rowe_pc_blocker/protocol_surface.py` and `tests/test_protocol.py`.
