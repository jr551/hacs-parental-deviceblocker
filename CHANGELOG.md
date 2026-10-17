# Changelog

## 0.12.0

### Added

- **Ask a parent to allow one app.** A blocked phone can now ask for a single app to be allowed instead of a parent editing the whole list: `POST /api/rowe_pc_blocker/{device_id}/unblock_request` records the request, `sensor.<device>_unblock_requests` shows what is waiting, and a dynamic `button.rowe_pc_blocker_approve_unblock_<request_id>` / `button.rowe_pc_blocker_reject_unblock_<request_id>` pair answers it. Approving appends the package to that phone's allowlist, so the block screen clears itself on the next state poll. Requests expire after 30 minutes and a second request for the same app is refused while one is waiting.
- **Chore challenge.** A parent can lock a phone behind a chore with `rowe_pc_blocker.send_challenge` (targeting `sensor.<device>_challenge`): the phone shows the phrase, the child takes a photo and types it back, and `POST …/challenge/accept` unlocks it. The photo arrives on `POST …/challenge/evidence`, is stored under `<config>/rowe_pc_blocker_evidence/` for 24 hours, and is shown to the parent through `image.<device>_challenge_evidence`. `rowe_pc_blocker.clear_challenge` cancels the challenge and deletes its photo.

## 0.11.0

### Added

- **Parent-approved phone setup.** A phone now asks to be set up instead of configuring itself: it sends the child's name, Home Assistant raises a pending request, and a parent approves or rejects it from the UI. `POST /api/rowe_pc_blocker/provision` returns `202 pending` with a request id; `GET /api/rowe_pc_blocker/provision/{request_id}` is polled until the parent decides. Requests expire after 30 minutes and approval reuses an existing phone entry for that child when one exists.
- `sensor.rowe_pc_blocker_pending_setups` plus dynamic `button.rowe_pc_blocker_approve_*` / `button.rowe_pc_blocker_reject_*` entities for those requests.
- **Per-child allowed apps managed from Home Assistant.** `text.<device>_allowed_apps` sets the apps a blocked phone may still open (empty means the client's built-in defaults), and `sensor.<device>_installed_apps` lists what the phone reports.
- **Cross-device block screen.** The state payload now carries a `family` object with the same child's other devices, so a blocked PC shows the child's phone status and a blocked phone shows the PC's.
- `rowe_pc_blocker_agent_stale` / `rowe_pc_blocker_agent_recovered` events, fired once per episode, plus a `last_seen` attribute on the agent-online sensor. `deploy/home-assistant/rowe_pc_blocker_agent_stale.yaml` alerts a parent when an agent stops checking in.
- Android: watchdog heartbeat so a phone with a dead accessibility poller still checks in, and a self-healing poll scheduler.

### Changed

- Android app is now labelled **Android Settings** with a gear icon, so it is not obviously a parental-control app on the child's launcher.
- Android setup is a single screen: Home Assistant address and the child's name, then wait for a parent to approve.
- Android location reporting requests a fresh fix when the cached one is stale, instead of only ever reading cached fixes.
- Android package queries use a `<queries>` launcher-intent declaration instead of `QUERY_ALL_PACKAGES`.

### Removed

- Android media/photo backup client code, permissions, and UI (the server side was removed in 0.10.0).

## 0.10.1

### Security

- Harden all `/api/rowe_pc_blocker/` views: constant-time device key checks, per-IP auth throttling, integration with Home Assistant `ip_ban` on `401`, uniform unauthorized responses, JSON body size limits, portal session cookies, and browser CSRF checks on `/portal/action` and `/parent_override`.
- Enforce ≥128-bit API keys, and add a **Reconfigure** flow to rotate a device key.
- Leak audit (`docs/LEAK_AUDIT.md`), gitleaks in CI, removal of generated README screenshots, and scrub of personal names from docs/examples (legacy `rowe_*` protocol identifiers unchanged).

### Added

- Tag-triggered release workflow: HACS zip, Android APK, Windows Inno Setup installer, and `SHA256SUMS` on GitHub Releases (optional signing via repository secrets).

## 0.10.0

### Removed

- Android S3 photo/video backup from Home Assistant options, credentials storage, presign logic, and the **Media backup** sensor.
- `s3_backup` module and related tests/documentation.

### Changed

- Config entry migration (version 2) strips legacy S3 option keys on upgrade so existing installs load without errors.
- Android backup HTTP endpoints remain but always report backup disabled (wire-compatible with older phone agents).
- Clearer config/options flow copy, field descriptions, and validation messages.
- Diagnostics download with secrets redacted.
- Brand assets and README refresh with setup screenshots.

### Added

- `docs/PROTOCOL.md` and `tests/test_protocol.py` pinning the agent HTTP contract.

### Notes for upgraders

- If you relied on server-mediated S3 backup, migrate to another backup strategy before upgrading; phones will see `enabled: false` on `/backup/config`.
- Windows agents require no changes.
