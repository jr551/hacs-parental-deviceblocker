# Changelog

## 0.13.1

### Fixed

- **The Windows agent no longer installs a second copy of itself.** The updater preferred the Inno Setup package, which installs under `Program Files`, but an agent laid down by `Install-RowePcBlocker.ps1` lives under `%ProgramData%\RowePcBlocker`. On those PCs the installer reported success while the running build stayed where it was, leaving a parallel copy behind and never falling back to the archive. The updater now picks the archive first when its own binaries sit under `%ProgramData%`, and the installer first everywhere else.

## 0.13.0

### Added

- **A parent answers a submitted challenge from the device page.** Accept and Send back buttons sit next to the evidence photo instead of leaving `approve_challenge` / `reject_challenge` as service calls only. `button.<device>_approve_challenge` and `button.<device>_reject_challenge` are available exactly while a submission is waiting, and the same rule drives a conditional card on the dashboard.
- **The wake alarm is a set of controls, not an options form.** `switch.<device>_wake_alarm`, `time.<device>_wake_alarm_time`, `select.<device>_wake_alarm_days` (Every day / Weekdays / Weekends / Custom, with the real list in a `days` attribute) and `number.<device>_wake_alarm_volume` set the alarm from the device page or a dashboard. They write runtime state, so moving a slider never reloads the integration, and the change reaches the phone on its next state poll. An alarm configured through the options flow still seeds the controls, and a value set by an entity wins afterwards.
- **Type a chore and the phone locks.** `text.<device>_new_challenge` starts a challenge from the device page or a dashboard: type the phrase, press enter, and the phone is locked behind it on its next state poll. The box always reads empty because it is a trigger, not a stored setting.
- **A location request that is still waiting says so.** `device_tracker.<device>_location` carries `request_pending` and `request_age_seconds`, so a request the phone has not answered reads as outstanding rather than as a button that did nothing.

### Changed

- **The notifications sensor says what arrived, not how many.** `sensor.<device>_notifications` reported `21`, which told a parent nothing. Its state now names the noisiest apps — `My Singing Monsters 8, WhatsApp 6, Roblox 5 +1 more` — the day's total moved to a `count` attribute, and `items` carries the messages themselves for a dashboard card to render.
- **Asking for an app is the bottom picker only.** The block screen no longer carries a **Request unblock** button next to the app the child was blocked on: it let the child fire a request at whatever happened to open, and the picker at the bottom of the same screen already lists every app they can ask about. The picker sends the same request.
- **A phone that stops checking in now costs the PC.** After 24 hours of silence from a phone that had been seen at least once, the PC takes over the screen with **Phone app tamper detected** — a phone that has gone quiet for a day looks exactly like the app having been removed or disabled — names the child, and counts down. After 15 minutes it shuts the PC down. The countdown is measured from the phone's own `last_seen`, so restarting the agent or the PC buys no extra time, and a phone that checks in before the grace expires clears the notice with nothing to undo.
- **The pause screen and the chore screen no longer look alike.** A parent's pause is a cool navy and the chore challenge is a saturated violet, so a child can tell at a glance whether they were paused or are being asked to do a job.

### Fixed

- **A local PIN unlock no longer hides a parent's block.** The phone's own unlock — granted when Home Assistant refuses an override or cannot be reached — was stored with only a deadline, and `Policy.enforced` let it turn every successful poll back into "unblocked" for the whole hour. Nothing could revoke it: `LocalOverride.clear` had no caller, so turning the switch off and on, or pressing block again, changed nothing and the phone kept reporting `monitoring` while Home Assistant said the block was `enforced`. The override now records when it was granted, and a block a parent starts afterwards — a fresh `enforce_at` — supersedes it, so toggling the switch revokes a local unlock. An override written by an older build has no grant timestamp, so its grant is derived from its deadline, which is what makes an existing stuck phone recover on its first poll after updating.
- **The enforcement sensor says why a block is not in force.** `sensor.<device>_enforcement_status` reports `monitoring` with a `detail` that names the cause — a local PIN unlock and how long it has left, a grace period and the seconds remaining, or an active extension — instead of an empty attribute a parent has to guess at.
- **The release build no longer fails on Windows.** `scripts/build-release.sh` called `zip`, which the Windows runner does not have, so the Windows installer job died before the GitHub release was created. It now uses `zip`, `7z` or `tar` — whichever the host provides — and the file list feeds the archiver, so `__pycache__` and macOS resource forks still stay out of the HACS zip.

## 0.12.0

### Added

- **Ask a parent to allow one app.** A blocked phone can now ask for a single app to be allowed instead of a parent editing the whole list: `POST /api/rowe_pc_blocker/{device_id}/unblock_request` records the request, `sensor.<device>_unblock_requests` shows what is waiting, and a dynamic `button.rowe_pc_blocker_approve_unblock_<request_id>` / `button.rowe_pc_blocker_reject_unblock_<request_id>` pair answers it. Approving appends the package to that phone's allowlist, so the block screen clears itself on the next state poll. Requests expire after 30 minutes and a second request for the same app is refused while one is waiting.
- **A phone may have three unblock requests waiting at once.** The block screen's **Request another app** picker makes asking cheap, so a fourth request is refused with `429 {"error": "A parent is already looking at your requests."}` (a duplicate or already-allowed package is still a 409). The state payload carries `unblock_requests_pending` and `unblock_requests_limit`, and `sensor.<device>_unblock_requests` exposes the same two as attributes, so the phone disables the picker before it hits the wall. A slot frees up when a request is approved, rejected or expires.
- **The block screen can ask for another app.** A **Request another app** button under the allowed-apps list opens the phone's launchable apps (label and package, this app left out) and sends the same `unblock_request` the per-app button sends. The button is disabled, and says why, while a parent is already looking at as many requests as they will take.
- **Chore challenge.** A parent can lock a phone behind a chore with `rowe_pc_blocker.send_challenge` (targeting `sensor.<device>_challenge`): the phone shows the phrase, the child takes a photo and types it back, and `POST …/challenge/submit` hands it to a parent. The child waits from there — `rowe_pc_blocker.approve_challenge` unlocks the phone, and `rowe_pc_blocker.reject_challenge` sends it back with the photo deleted so the chore can be done again. The photo arrives on `POST …/challenge/evidence`, is stored under `<config>/rowe_pc_blocker_evidence/` for 24 hours, and is shown to the parent through `image.<device>_challenge_evidence`. `rowe_pc_blocker.clear_challenge` cancels the challenge and deletes its photo.
- **Daily notification dumps.** A phone posts one dump a day to `POST /api/rowe_pc_blocker/{device_id}/notifications` (at most 256 KiB), and `sensor.<device>_notifications` shows the day's count with a per-app summary and the most recent items. Only the latest dump is kept, a dump for a day older than the stored one is ignored, and the dump is trimmed to what the sensor shows so the state attributes stay inside the recorder's 16 KiB limit. Notification titles and bodies are redacted from diagnostics.
- The state payload's `family` peers now carry the peer's `challenge` object, so a child's PC can show that their phone is waiting on a chore.
- **On-demand location.** A phone no longer reports position on every heartbeat: a parent presses `button.<device>_request_location`, the request rides the phone's next state poll as `location_request`, and the phone posts one fix to `POST /api/rowe_pc_blocker/{device_id}/location`. The request expires after 10 minutes, a second press replaces it, and an answer for an id that is no longer current is refused, so a late answer cannot overwrite a newer request. The fix shows on `device_tracker.<device>_location`, which is unknown until the first answer and carries `location_age_seconds` (the phone's `fix_age_seconds` plus the time since the answer arrived), `location_provider`, `reported_at` and `requested_at`.
- **The PC shows the phone's chore challenge.** When the child's phone has an active challenge the PC says so full screen, naming the child and the phrase, and lets them keep working for five minutes before the notice becomes a hard kiosk. The grace is measured from when that PC first saw the challenge id and is recorded by the service under `%ProgramData%\RowePcBlocker\challenge-grace.json`, so restarting the agent or the PC does not restart it. A parent override suppresses the notice, and it clears as soon as the challenge is accepted or cleared.
- **The PC freezes when the phone stops checking in.** A phone that is offline with a `last_seen` older than 24 hours freezes the PC with **Parental control cannot be verified. Please see a parent.** A child with no phone entry, a shorter outage, or an unknown `last_seen` never freezes it, and the freeze clears as soon as the phone checks in again.
- **The Windows agent updates itself.** The service checks the public GitHub release once an hour, downloads a newer `ParentalDeviceBlocker-<version>-win-x64-setup.exe` (or `-win-x64.zip`) into `%ProgramData%\RowePcBlocker\updates\`, verifies it against the release's `SHA256SUMS`, and then runs the installer or the archive's `Upgrade-Installed-RowePcBlocker.ps1`. It never updates while the PC is blocked or a block is requested, and every failure is logged and retried on the next hourly check.
- **The phone updates itself.** The Android app checks the public release on its minute poll (the hourly job is the backstop), downloads a newer `ParentalDeviceBlocker-<version>.apk`, verifies it against the release's `.sha256` and against the running app's signing certificate, and then shows an **Update needed** screen with an **Install** button. The screen cannot be dismissed, reappears on every poll, and clears once the new version is running. It never starts while a challenge is active, and every failure is logged and retried on the next check.
- **The phone collects the day's notifications.** With notification access granted — the setup screen offers the shortcut and says plainly what is collected — the phone records each posted notification (package, app name, title, text, time) and posts the previous day's dump at local midnight, with a catch-up on the next poll.
- **On-demand location.** The phone no longer reads a position on its own: nothing touches the location provider until a parent asks from Home Assistant (`location_request` on the state poll). The phone then fetches one fresh fix from the cheapest enabled provider, posts it to `POST /api/rowe_pc_blocker/{device_id}/location` with the real provider, accuracy and fix age, and stops. A request it cannot answer inside 20 seconds is left unanswered rather than filled with a stale position, and a request id is answered at most once.

### Changed

- **The challenge photo is taken inside the lock screen.** The phone no longer hands the shot to the system camera app: the challenge panel shows its own preview and shutter, so the evidence photo can always be taken. The lock screen is what covers every disallowed app, so an external camera activity was covered by our own lock the moment anything re-raised it, and the child could never take the photo. The app now asks for the camera permission the first time, and the phrase is the first gate — **Take photo** stays disabled until the typed answer matches, so the pulse and the enabled state never disagree.
- **A rejected challenge is visible, and the wait says when it ends.** A parent's rejection turns the challenge screen red and the phrase shows `— attempt N`; the red clears as soon as the child starts again. The wait counts down to `auto_approve_at` (falling back to `submitted_at` plus two hours, then to the phone's own submit time) and says when the challenge approves itself. The phone still never unlocks on that countdown: only the payload dropping the challenge does.
- **A release whose asset name is not a plain version is refused.** The updater validates the version in a release asset name against digits and dots before it is used anywhere, and the downloaded APK is kept under a fixed file name, so no release-supplied text can reach a file path.
- **A rejected challenge counts as an attempt.** The `challenge` payload and `sensor.<device>_challenge` carry `attempt` (1-based): `send_challenge` starts at 1, `reject_challenge` increments it, and approving or clearing leaves it alone. It is persisted with the rest of the challenge state, so a restart does not reset the count and the phone can tell the child which try they are on.
- **A submitted challenge approves itself after two hours.** If nobody approves or rejects it within two hours of `submitted_at`, the challenge approves itself so a child is never locked indefinitely. The check runs whenever the challenge is read — the phone's state poll and the challenge sensor's 30-second tick — so no scheduler is involved, and the payload goes null on that same poll. A parent's decision before the deadline wins. The `challenge` payload and the sensor carry `auto_approve_at` (`submitted_at + 2 hours`) while the challenge is submitted, and null otherwise.
- **A chore challenge now needs a parent's approval.** `sensor.<device>_challenge` runs `none` → `pending` → `submitted` → `approved`, and the state payload's `challenge` object (now carrying `status` and `submitted_at`) is null once it is approved — that null is how the phone knows to unlock. `POST …/challenge/accept` is replaced by `POST …/challenge/submit`, which returns `{"ok": true, "status": "submitted"}`; the phone no longer accepts its own challenge. The sensor's `accepted_at` attribute is replaced by `submitted_at` and `approved_at`, and the new `approve_challenge` / `reject_challenge` services are what a parent's automation drives.
- **The phone's chore challenge is enforced like a block.** An unanswered challenge now blocks disallowed apps through the same accessibility path as a parent block, so Back, Home and the launcher cannot dismiss it; the phrase screen is the block screen, the allowed apps stay usable, and the challenge reappears on every poll until it is accepted or cleared. The separate challenge activity is gone, the lock no longer plays a sound, and the challenge survives a poll that fails while the child is taking the photo.
- **The challenge photo is compressed harder.** 1280 px at JPEG quality 70 first, then 60/50/40, then 960 px, until the encoded photo fits 300 KiB — far below Home Assistant's 5 MiB cap — with the EXIF orientation applied so the photo is not sideways.
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
