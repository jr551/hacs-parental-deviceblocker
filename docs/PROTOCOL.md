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
| `challenge` | object \| null | Active chore challenge: `{"id", "phrase", "sent_at", "status", "submitted_at"}`, or null once a parent approves it |
| `location_request` | object \| null | Pending on-demand fix: `{"id", "requested_at"}`, or null when nothing is pending |
| `unblock_requests_pending` | integer | Apps this phone is waiting on a parent to allow |
| `unblock_requests_limit` | integer | How many it may have waiting at once |
| `family` | object | This child's other devices: `{"phone": peer\|null, "pc": peer\|null}` |

A `family` peer is `{"device_id", "device_type", "blocked", "message", "agent_online", "last_seen", "active_application", "challenge"}`, where `last_seen` is ISO-8601 or null and `challenge` is the same `{"id", "phrase", "sent_at", "status", "submitted_at"}` object the peer's own state poll carries (null when it has none). Peers are the same child's other devices, matched on the chore-manager child name; the requesting device is never its own peer. Challenges are phone-only, so a PC peer always reports `"challenge": null`; the key is present on both peers so the shape is uniform.

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

The Android agent adds one optional field to the same body:

| Field | Type | Notes |
|-------|------|-------|
| `installed_apps` | array | `[{"package", "label"}]`; sent only when the set changed |

Position is **not** reported here: it is on demand (see below), so a heartbeat never carries one.

**Response 200:** `{"ok": true}`

### `POST /api/rowe_pc_blocker/{device_id}/location`

Android only. A parent presses **Request location** in Home Assistant, the request rides the phone's next state poll in `location_request`, and the phone posts one fix back. The request expires after **10 minutes**, so a phone that was offline cannot answer later and have the answer look current.

**Request** JSON object:

```json
{
  "request_id": "0123456789abcdef0123456789abcdef",
  "latitude": 51.37,
  "longitude": -0.28,
  "accuracy": 12.5,
  "provider": "fused",
  "fix_age_seconds": 3
}
```

| Field | Type | Notes |
|-------|------|-------|
| `request_id` | string | The `id` from `location_request` |
| `latitude`, `longitude` | number | Required; latitude −90…90, longitude −180…180 |
| `accuracy` | number | Metres, optional |
| `provider` | string | `fused`, `gps`, `network`, … optional |
| `fix_age_seconds` | integer | How old the fix already was when the phone sent it, optional |

**Response 200:** `{"ok": true}`

**Response 400:** `{"error": "…"}` — the body is not a JSON object, or the coordinates are missing or out of range. The request stays pending, so the phone may try again.

**Response 404:** `{"error": "That location request has expired."}` — the id is not the current one, or the request has expired. A late answer cannot overwrite a newer request.

A successful answer clears the pending request and stores the fix. `device_tracker.<device>_location` shows it: the state is unknown until the first fix arrives, and `location_age_seconds` (the phone's `fix_age_seconds` plus the time since the answer arrived) is what tells a parent how old the position is. `requested_at` is when the parent last asked, `reported_at` when the answer arrived, and `location_provider` what the phone named.

### `POST /api/rowe_pc_blocker/{device_id}/notifications`

Android only. The phone captures notifications and posts one dump a day, so a parent can see what arrived without opening the phone. Home Assistant keeps only the latest dump per device.

**Request** JSON object, at most **256 KiB**:

```json
{
  "date": "2026-10-17",
  "count": 137,
  "truncated": false,
  "items": [
    {
      "package": "com.whatsapp",
      "app": "WhatsApp",
      "title": "Mum",
      "text": "Tea is ready",
      "posted_at": "2026-10-17T17:30:00+00:00"
    }
  ]
}
```

| Field | Type | Notes |
|-------|------|-------|
| `date` | string | The day the dump covers, `YYYY-MM-DD`. Required |
| `count` | integer | How many notifications the phone captured that day |
| `truncated` | boolean | True when the phone hit its own capture cap |
| `items` | array | `[{"package", "app", "title", "text", "posted_at"}]`; the order does not matter, Home Assistant sorts by `posted_at` |

**Response 200:** `{"ok": true}`

**Response 400:** `{"error": "…"}` — the body is not a JSON object, or `date` is missing or not a `YYYY-MM-DD` date.

**Response 413:** `{"error": "That notification dump is too large."}` — over 256 KiB.

A dump for a day **older** than the stored one is answered **200** and ignored, so a late retry cannot overwrite a newer day. The same day may be re-sent, and a newer day replaces the stored one.

`sensor.<device>_notifications` is the parent-facing view: the state is the stored day's `count`, with `date`, `truncated`, `received_at`, `apps` (`[{"package", "app", "count"}]`, most-used first, capped at 40) and `items` (the most recent, newest first, capped at 100 and by a 10 KiB attribute budget) attributes. The dump is trimmed to those caps when it is stored, so the runtime store stays small and the state attributes stay inside the recorder's 16 KiB limit. Notification titles and bodies are redacted from diagnostics.

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

## Buying an hour with points (Android)

### `POST /api/rowe_pc_blocker/{device_id}/points_hour`

**Request:** `{}` (empty object)

Spends the child's Taskmate points for one hour of phone time. The phone calls this from the block screen; the parent never types anything.

**Response 200** — the hour was granted:

```json
{
  "ok": true,
  "minutes": 60,
  "points": 200,
  "until": "2026-01-01T12:05:00+00:00"
}
```

**Response 409** — the phone is not blocked: `{"error": "This phone is not blocked."}`

**Response 409** — the block lifted between the check and the spend: `{"error": "Points were spent but the phone is no longer blocked."}`

**Response 404** — no Taskmate child for this phone: `{"error": "Taskmate points are not available for this phone."}`

**Response 402** — not enough points:

```json
{
  "error": "Not enough points. An hour costs 200.",
  "points_balance": 40,
  "points_hour_cost": 200
}
```

**Response 502** — the Taskmate service call failed: `{"error": "Could not spend Taskmate points."}`

A successful call fires the `rowe_pc_blocker_points_hour` event with `device_id`, `device_name`, `child_id`, `child_name`, `points`, `minutes`, and `until`.

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

## Asking a parent to allow an app (Android)

A blocked phone shows the block screen; when the child taps an app that is not
allowed, the phone asks a parent to allow it. The request waits for a parent on
the dashboard and expires after **30 minutes**, like a setup request. There is
no polling endpoint: the phone already polls `GET …/state` about once a minute,
so the block screen clears itself once the package appears in `allowed_apps`.

### `POST /api/rowe_pc_blocker/{device_id}/unblock_request`

Device credentials required, like every other device endpoint.

**Request:**

```json
{
  "package": "com.roblox.client",
  "label": "Roblox"
}
```

`label` is what the parent sees on the dashboard; it is truncated to 128 characters.

**Response 200** — the request is waiting for a parent:

```json
{
  "ok": true,
  "request_id": "0123456789abcdef0123456789abcdef",
  "status": "pending"
}
```

**Response 400** — `package` is not an Android package name: `{"error": "That is not an Android package name."}`

**Response 409** — the app is already allowed, either by the parent's list or by the agent's built-in defaults: `{"error": "That app is already allowed on this phone."}`

**Response 409** — a request for that app is already waiting: `{"error": "A parent already has a request for that app."}`

**Response 429** — the phone already has **3** requests waiting on a parent: `{"error": "A parent is already looking at your requests."}`

At most one pending request exists per device and package, and at most **3**
per device at any time — the block screen's **Request another app** picker makes
asking cheap, so the cap is what stops a child filling the dashboard. The phone
reads `unblock_requests_pending` and `unblock_requests_limit` from the state
payload and disables the picker before it hits the wall; a slot frees up as soon
as a request is approved, rejected or expires.

Approving appends the package to that device's allowlist (the same value the
`text.<device>_allowed_apps` entity holds and the state payload sends) and
removes the request; rejecting just removes it. The dashboard shows
`sensor.<device>_unblock_requests` (state = count, with `requests`,
`unblock_requests_pending` and `unblock_requests_limit` attributes) and a
dynamic `button.rowe_pc_blocker_approve_unblock_<request_id>` /
`button.rowe_pc_blocker_reject_unblock_<request_id>` pair per request.

## Chore challenge (Android)

A parent can lock a phone behind a chore: the phone shows a phrase, the child
takes a photo and types the phrase back, and the phone hands it to a parent.
The child waits from there — a parent approves the photo, and only then does
the challenge leave the state payload and the phone unlock. The challenge
travels on the state poll, so a new one reaches the phone within a minute and
no push channel is needed.

The challenge runs `none` → `pending` (sent, the child fills it in) →
`submitted` (photo uploaded, waiting for a parent) → `approved` (a parent
approved it), and back to `none` when a parent clears it.

A `submitted` challenge **approves itself two hours after `submitted_at`** if
nobody decides, so a child is never locked indefinitely. The check runs
whenever the challenge is read — the state poll below and the challenge
sensor — so no scheduler is involved, and the payload goes null on that same
poll. A parent approving or rejecting before the deadline wins.

The `challenge` object in the state payload is present while the state is
`pending` or `submitted`, and **null** once it is `approved` or `none` — that
null is how the phone knows the chore is done and it may unlock. It carries
`{"id", "phrase", "sent_at", "status", "submitted_at", "auto_approve_at", "attempt"}`,
where `status` is `pending` or `submitted`, `submitted_at` is ISO-8601 or null,
`auto_approve_at` is `submitted_at + 2 hours` while `submitted` and null
otherwise, and `attempt` is the 1-based number of the try the child is on — a
rejected submission counts as an attempt, so the phone can say "attempt 2"
instead of restarting the count silently.

### `POST /api/rowe_pc_blocker/{device_id}/challenge/submit`

Device credentials required, like every other device endpoint.

**Request:** `{"id": "0123456789abcdef0123456789abcdef"}`

The id is the one from the `challenge` object in the state payload.

**Response 200** — the challenge is waiting for a parent:

```json
{
  "ok": true,
  "status": "submitted"
}
```

**Response 404** — there is no challenge, this id is not the current one, or a parent has already approved it: `{"error": "That challenge is no longer active."}`

**Response 409** — no photo has been uploaded for this challenge: `{"error": "Take the photo first."}`

Submitting is idempotent: a retry with the current id is answered **200** and
keeps the first submission time. The phone must keep the lock up until the
`challenge` object goes null.

### `POST /api/rowe_pc_blocker/{device_id}/challenge/evidence`

Device credentials required. The body is the JPEG itself rather than JSON, so
the phone can stream the camera output straight through.

**Request:** raw JPEG bytes, `Content-Type: image/jpeg`

**Response 200** — stored:

```json
{
  "ok": true,
  "expires_at": "2026-10-18T12:00:00+00:00"
}
```

**Response 404** — there is no active challenge: `{"error": "That challenge is no longer active."}`

**Response 413** — larger than 5 MiB: `{"error": "That photo is too large."}`

**Response 415** — not `image/jpeg`: `{"error": "Send the photo as image/jpeg."}`

Photos are stored at `<config>/rowe_pc_blocker_evidence/<device_id>/<challenge_id>.jpg`
and deleted after **24 hours**, pruned on every upload and at integration setup,
so a photo cannot outlive its window even if nothing new arrives. Clearing the
challenge deletes its photo immediately.

A parent looks at the photo through `image.<device>_challenge_evidence`, which
Home Assistant serves through its own authenticated image proxy — the device key
is never involved, and the entity has no image while nothing is stored.

### Challenge services

| Service | Target | Data | Effect |
|---------|--------|------|--------|
| `rowe_pc_blocker.send_challenge` | `sensor.<device>_challenge` | `phrase` (required, 1–200 characters, trimmed) | Starts a new challenge with a fresh id; the sensor becomes `pending` |
| `rowe_pc_blocker.approve_challenge` | `sensor.<device>_challenge` | — | Approves a `submitted` challenge; the sensor becomes `approved` and the payload goes null, so the phone unlocks. Only needed to decide before the two-hour deadline |
| `rowe_pc_blocker.reject_challenge` | `sensor.<device>_challenge` | — | Sends a `submitted` challenge back to the child and deletes its photo; the sensor returns to `pending` and `attempt` goes up by one |
| `rowe_pc_blocker.clear_challenge` | `sensor.<device>_challenge` | — | Cancels the challenge, approved or not, and deletes its photo; the sensor returns to `none` |

`sensor.<device>_challenge` is `none`, `pending`, `submitted`, or `approved`,
with `phrase`, `sent_at`, `submitted_at`, `approved_at`, `auto_approve_at`,
`attempt`, `evidence_at`, and `evidence_expires_at` attributes. Approving — by a parent or
by the two-hour deadline — keeps the photo until the challenge is cleared, so a
parent can still look at what was approved.

## Contract tests

Machine-readable pins live in `custom_components/rowe_pc_blocker/protocol_surface.py` and `tests/test_protocol.py`.
