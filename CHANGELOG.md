# Changelog

## 0.12.0

### Added

- **Ask a parent to allow one app.** A blocked phone can now ask for a single app to be allowed instead of a parent editing the whole list: `POST /api/rowe_pc_blocker/{device_id}/unblock_request` records the request, `sensor.<device>_unblock_requests` shows what is waiting, and a dynamic `button.rowe_pc_blocker_approve_unblock_<request_id>` / `button.rowe_pc_blocker_reject_unblock_<request_id>` pair answers it. Approving appends the package to that phone's allowlist, so the block screen clears itself on the next state poll. Requests expire after 30 minutes and a second request for the same app is refused while one is waiting.
- **Chore challenge.** A parent can lock a phone behind a chore with `rowe_pc_blocker.send_challenge` (targeting `sensor.<device>_challenge`): the phone shows the phrase, the child takes a photo and types it back, and `POST …/challenge/accept` unlocks it. The photo arrives on `POST …/challenge/evidence`, is stored under `<config>/rowe_pc_blocker_evidence/` for 24 hours, and is shown to the parent through `image.<device>_challenge_evidence`. `rowe_pc_blocker.clear_challenge` cancels the challenge and deletes its photo.
- **Daily notification dumps.** A phone posts one dump a day to `POST /api/rowe_pc_blocker/{device_id}/notifications` (at most 256 KiB), and `sensor.<device>_notifications` shows the day's count with a per-app summary and the most recent items. Only the latest dump is kept, a dump for a day older than the stored one is ignored, and the dump is trimmed to what the sensor shows so the state attributes stay inside the recorder's 16 KiB limit. Notification titles and bodies are redacted from diagnostics.
- The state payload's `family` peers now carry the peer's `challenge` object, so a child's PC can show that their phone is waiting on a chore.
- **On-demand location.** A phone no longer reports position on every heartbeat: a parent presses `button.<device>_request_location`, the request rides the phone's next state poll as `location_request`, and the phone posts one fix to `POST /api/rowe_pc_blocker/{device_id}/location`. The request expires after 10 minutes, a second press replaces it, and an answer for an id that is no longer current is refused, so a late answer cannot overwrite a newer request. The fix shows on `device_tracker.<device>_location`, which is unknown until the first answer and carries `location_age_seconds` (the phone's `fix_age_seconds` plus the time since the answer arrived), `location_provider`, `reported_at` and `requested_at`.
- **The PC shows the phone's chore challenge.** When the child's phone has an active challenge the PC says so full screen, naming the child and the phrase, and lets them keep working for five minutes before the notice becomes a hard kiosk. The grace is measured from when that PC first saw the challenge id and is recorded by the service under `%ProgramData%\RowePcBlocker\challenge-grace.json`, so restarting the agent or the PC does not restart it. A parent override suppresses the notice, and it clears as soon as the challenge is accepted or cleared.
- **The PC freezes when the phone stops checking in.** A phone that is offline with a `last_seen` older than 24 hours freezes the PC with **Parental control cannot be verified. Please see a parent.** A child with no phone entry, a shorter outage, or an unknown `last_seen` never freezes it, and the freeze clears as soon as the phone checks in again.
- **The Windows agent updates itself.** The service checks the public GitHub release once an hour, downloads a newer `ParentalDeviceBlocker-<version>-win-x64-setup.exe` (or `-win-x64.zip`) into `%ProgramData%\RowePcBlocker\updates\`, verifies it against the release's `SHA256SUMS`, and then runs the installer or the archive's `Upgrade-Installed-RowePcBlocker.ps1`. It never updates while the PC is blocked or a block is requested, and every failure is logged and retried on the next hourly check.
- **The phone updates itself.** The Android app checks the public release on its minute poll (the hourly job is the backstop), downloads a newer `ParentalDeviceBlocker-<version>.apk`, verifies it against the release's `.sha256` and against the running app's signing certificate, and then shows an **Update needed** screen with an **Install** button. The screen cannot be dismissed, reappears on every poll, and clears once the new version is running. It never starts while a challenge is active, and every failure is logged and retried on the next check.
- **The phone collects the day's notifications.** With notification access granted — the setup screen offers the shortcut and says plainly what is collected — the phone records each posted notification (package, app name, title, text, time) and posts the previous day's dump at local midnight, with a catch-up on the next poll.

### Changed

- **The phone's chore challenge is enforced like a block.** An unanswered challenge now blocks disallowed apps through the same accessibility path as a parent block, so Back, Home and the launcher cannot dismiss it; the phrase screen is the block screen, the allowed apps stay usable, and the challenge reappears on every poll until it is accepted or cleared. The separate challenge activity is gone.
- **The phone's foreground notification no longer gives the app away.** It reads **Android Settings** / "Keeping this device up to date." on a neutral, silent, minimum-importance channel with no badge, no sound and no content intent.
- **Entity coherence.** Every per-device entity now follows one rule: what a parent watches or acts on stays on the device card, and what only explains the machinery behind it is `diagnostic` (active window, portal/enforcement status, screen assessment, activity summaries, installed apps). The challenge and screen-safety sensors are enum sensors, the count sensors are measurements, and the agent version moved from two sensor attributes to the device's firmware version, which is pushed to the device registry when it changes so it does not go stale.
- **Availability.** Telemetry that only an agent check-in refreshes — active application, active window, portal/enforcement status, installed apps — now reports `unavailable` while the agent is silent instead of leaving a stale reading on the dashboard. The block switch, the parent-editable text entities, and the challenge/unblock/notification sensors stay available, and the agent-online sensor carries `last_seen_age_seconds` and `online_timeout_seconds`.
- `sensor.<device>_portal_status` is now created for PCs only and `sensor.<device>_enforcement_status` for phones only: both reported the same agent field with different meanings. The enforcement sensor's `fail_open` / `accessibility_disabled` attributes are replaced by one `degraded` flag that also covers `accessibility_interrupted`.
- The block switch reports `block_reason` (`off`, `grace_period`, `extension`, `enforced`) and `extension_until`, so a dashboard can show why a requested block is not in force.
- `device_tracker.<device>_passive_location` is renamed to `device_tracker.<device>_location` and its `passive_only` attribute is gone; the old entity id is orphaned and can be deleted from the entity registry. The tracker keeps the last fix while the phone is offline and exposes its age instead of going unavailable, since the fix is a stored answer rather than a live feed.

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
