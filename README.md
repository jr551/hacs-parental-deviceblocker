# Parental Device Blocker

Home Assistant parental controls for Windows PCs and Android phones, with optional activity reporting, a save-work grace period, a child-scoped points portal, and privacy-conscious screen assessment.

> [!WARNING]
> This project is an experimental parental-control aid, not a tamper-proof security boundary. Supervise initial setup, keep a separate parent administrator account, and test emergency recovery before relying on enforcement.

## Features

Each configured device provides Home Assistant entities for:

- requested and effective blocked state;
- an editable block message;
- agent connectivity and foreground application/window telemetry;
- optional passive Android location based only on an already-cached fix;
- optional read-only VNC screen assessment and bounded text-only reports.

Windows uses a `LocalSystem` service plus a companion process in the child session. Android uses an Accessibility service and permits WhatsApp, Google Maps, System UI, keyboard, permission, and in-call components while effectively blocked.

Android enforcement is deliberately fail-open. Missing configuration, network or authentication errors, malformed or contradictory policy data, Accessibility interruption, service shutdown, and stale grace responses immediately clear Android enforcement. Only a fresh valid response with `blocked: true` captures foreground apps.

Windows retains its last successfully applied state during a temporary Home Assistant outage. That difference is intentional and should be considered when choosing the project for a household.

## Compatibility identifiers

The integration began as a private household project. To preserve upgrades for existing installations, these internal identifiers remain unchanged:

- Home Assistant domain and directory: `rowe_pc_blocker`;
- API route prefix: `/api/rowe_pc_blocker/`;
- legacy API header: `X-Rowe-Key` (new agents use `X-Device-Blocker-Key`);
- Android application ID: `lol.rowe.blocker`;
- Windows service and binary identifiers beginning with `RowePc`.

They are compatibility tokens, not current product branding. New UI, documentation, releases, and device names use **Parental Device Blocker**.

## Security model

- Every device has its own randomly generated API key.
- Device endpoints accept that narrow key, not a Home Assistant administrator token.
- There is no universal parent PIN. Parent override is disabled until a PIN is explicitly configured in that device's integration options.
- The Android configuration screen requires a parent-chosen local PIN on first launch; no recovery or factory PIN exists. Two wrong local PIN attempts lock that screen for 15 minutes.
- The Android device API key is sealed with the Android Keystore. A leftover plaintext value is migrated on the next launch.
- Android reports fail-open and Accessibility-off to the Enforcement status sensor. Blocking still fails open; it does not keep a phone locked through a Home Assistant or network outage.
- Parent-override failures are rate limited after two incorrect attempts.
- The built-in VNC client is screenshot-only and implements no keyboard, pointer, clipboard-write, or file-transfer operations.
- Screen captures are resized in memory, sent once to the configured provider, and discarded.

Use HTTPS whenever possible. A plain `http://` Home Assistant URL exposes the device key to other systems able to observe that network. Never expose the device endpoints directly to the public internet.

Report security issues privately as described in [SECURITY.md](SECURITY.md).

## Home Assistant installation

In HACS:

1. Open **HACS → Integrations → Custom repositories**.
2. Add `https://github.com/jr551/hacs-parental-deviceblocker` as an **Integration** repository.
3. Install **Parental Device Blocker** and restart Home Assistant.
4. Add one integration entry per PC or phone.
5. Copy each generated device key only to its matching agent.

The internal domain remains `rowe_pc_blocker` for compatibility. An optional YAML import example is at `deploy/home-assistant/rowe_pc_blocker.yaml.example`.

To enable the one-hour parent override, open the integration entry's options and set a unique parent PIN. Leaving the field blank keeps an existing PIN. **Disable parent PIN override** clears it and makes the endpoint return unavailable until a new PIN is configured.

## Windows package

Build on macOS or Linux with .NET 8, or with Docker when .NET is absent:

```bash
./scripts/build-release.sh 0.9.37
```

The script creates:

- `artifacts/ParentalDeviceBlocker-0.9.37-win-x64.zip`;
- `artifacts/parental_device_blocker-hacs-0.9.37.zip`.

Extract the Windows ZIP and run an Administrator PowerShell prompt:

```powershell
.\Install-RowePcBlocker.ps1 `
  -HomeAssistantUrl 'https://homeassistant.example' `
  -DeviceId 'child-pc' `
  -DeviceApiKey 'KEY_FROM_HOME_ASSISTANT' `
  -ChildUsername 'child'
```

The installation starts monitor-only with the save-work UI disabled. Confirm telemetry first, then enable the UI and enforcement:

```powershell
.\Enable-UserInterface.ps1
.\Enable-Enforcement.ps1
```

Both scripts accept `-Disable`. The parent administrator can recover with:

```powershell
.\Emergency-Unblock.ps1 -ChildUsername 'child'
```

### Optional points portal

Add `-EnablePortal` during installation to show the built-in points/chores portal while blocked. It integrates with entity data shaped like the Home Assistant **Family Chore Manager** integration and resolves exactly one child from the configured local account or device name. It never exposes another child's activity.

A self-hosted alternative can be configured without an address bar:

```powershell
.\Set-PortalUrl.ps1 -PortalUrl 'https://example.internal/child-panel'
```

Do not put Home Assistant tokens or passwords in a custom URL.

While a PC is not blocked, the activity app reports portal status `idle` (or `disabled` when the portal was not enabled). It no longer leaves Home Assistant on `starting` for the whole unblocked session. `idle` and `disabled` are healthy. Real portal failures still use a `portal_` status and raise `rowe_pc_blocker_portal_error`.

## Android package

Home Assistant **0.9.37** and Android **0.4.10** ship together. Install the integration from this release before treating an older GitHub release asset as the current phone agent. The phone package is not part of the HACS zip.

The Docker build runs unit tests, lint, and a signed **non-debuggable** release APK:

```bash
./scripts/build-android-docker.sh
```

The output is `artifacts/android/ParentalDeviceBlocker-0.4.10.apk` plus `ParentalDeviceBlocker-0.4.10.apk.sha256`. This is still a sideload package, not a Play Store package. `./scripts/build-android-docker.sh debug` remains available for disposable emulator tests that need a user-installed CA. Do not install the debug APK on a phone a child uses.

The first release build creates a protected signing key and password file under `~/.config/parental-device-blocker/`. The key enters Docker only as a BuildKit secret and is never copied into the image or repository. Set `PARENTAL_DEVICE_BLOCKER_REUSE_LEGACY_KEY=1` only for a private migration build that must upgrade the former household-signed debug APK. Android will otherwise require that legacy build to be uninstalled before installing 0.4.10; uninstalling clears its local PIN, configuration, and backup index.

On first launch, the parent must create and confirm a local configuration PIN before entering the Home Assistant URL, device ID, and device key. There is no default PIN. Two wrong attempts lock the configuration screen for 15 minutes. Losing the PIN requires clearing app data and provisioning the app again. Use HTTPS. Plain HTTP is accepted only for local names such as `http://192.168.x.x`, `http://homeassistant.local`, or a single-label LAN host.

After configuration:

1. Enable **Parental Device Blocker** under Android Accessibility settings.
2. Allow restricted settings if Android requests it.
3. Tap **Allow unrestricted battery** in the app, then on Samsung set Battery → Unrestricted and remove the app from Sleeping apps, Deep sleeping apps, and Put unused apps to sleep. Re-check this after One UI updates. Galaxy S20 (SM-G980F/G981B), S20+ (SM-G986B), and Galaxy S22 follow the same One UI path; the S22 is often more aggressive, so prove it with an overnight idle and a few days of use before relying on it.
4. On Xiaomi/MIUI, enable Autostart and background pop-up windows.
5. Turn Developer options and USB debugging **off** after setup.
6. Confirm the agent online sensor and Enforcement status (`monitoring`) while the block switch is off.
7. Perform a supervised block test and verify WhatsApp, Maps, Back, Home, and emergency calling.
8. Disconnect Home Assistant during a block and verify that the phone fails open and Enforcement status becomes `fail_open` (or the agent goes offline if the report cannot be delivered).

The deploy checklist is [docs/android-deploy-checklist.md](docs/android-deploy-checklist.md). Pilot one phone and soak it before a second phone.

Each phone is its own integration entry, with its own device id and API key. Set **Child name** to the chore-manager child that phone belongs to (the same match the points portal already uses: the child name, or that name inside the device name). Buying an hour spends that child's Taskmate points. Nothing in the phone app is tied to one child.

The wake alarm is configured only on that device's integration options: on/off, time, weekdays (Monday–Friday until you change them), and alarm volume **0–100**. 100 is the phone's maximum alarm-stream volume, and 0 is silent. The phone reads `wake_alarm` on the existing once-a-minute state poll, caches the last schedule, and rings on the alarm stream at that volume. The person holding the phone can stop a ringing alarm. If Home Assistant cannot be reached, the block fails open and the last cached alarm stays scheduled.

Samsung Galaxy S20 (SM-G980F/G981B, One UI 2.5–5.x, Android 10–13), S20+ (SM-G986B), and Galaxy S22 (Android 13–14) are the documented targets, with targetSdk 35 and minSdk 29. The 0.4.10 build handles Android 13 scoped storage (READ_MEDIA_IMAGES/VIDEO) and Android 14 partial user-selected media (READ_MEDIA_VISUAL_USER_SELECTED), and treats wireless charging as external power. WhatsApp/Maps `getLaunchIntentForPackage` only resolves the current user/profile — clones in Samsung Secure Folder / Dual Messenger are isolated and will not be launched; the primary profile’s install is used. Test Secure Folder explicitly if you rely on it.

Accessibility enforcement is parental friction, not Device Owner enforcement. There is no uninstall protection in this build. A determined user may disable Accessibility, uninstall the app, enter safe mode, or reset the phone. Disabling Accessibility is reported to Home Assistant while the watchdog process is still alive; uninstalling the app makes the agent go offline. A foreground watchdog and a 15-minute backup job reduce silent Samsung kills, but they do not survive Force stop.

### Optional S3 photo and video backup

An Android entry can optionally back up the device's MediaStore photos and videos to a path-style S3-compatible bucket. Configure it from that entry's Home Assistant options with:

- an HTTPS S3 endpoint and region;
- a bucket;
- a write-only access key and secret;
- a unique device/user folder prefix.

The S3 credentials remain in Home Assistant and are never returned to Android. For each file, the authenticated phone requests a 15-minute SigV4 PUT URL that is restricted to the configured prefix and signed content length. Use a dedicated S3 key limited to `PutObject` for only that bucket/prefix; do not use an administrator key.

The parent must grant Android photo and video access from the PIN-protected setup screen. Every media upload requires the phone to be connected to external power. The first full-library sync additionally requires Wi-Fi; after it completes, Android schedules quiet powered incremental batches on any connected network. Home Assistant receives a filename, relative MediaStore path, and byte count transiently to construct each signed object path, but does not retain that metadata, thumbnails, or media content. The status endpoint reports only counts and bounded errors.

The resumable local index skips unchanged files and overwrites the same object when Android reports that file as modified. Deleting a file from the phone does not delete its S3 copy. Changing the endpoint, bucket, region, or prefix resets the local index and performs a new powered, Wi-Fi-only initial sync. The Home Assistant status sensor is device-reported progress, so confirm object counts and perform a restore from S3 independently.

Empty MediaStore entries and individual files larger than 20 GiB are skipped and counted explicitly in the status sensor instead of being reported as successfully backed up.

Files are uploaded byte-for-byte, including embedded EXIF/location metadata. Transport uses HTTPS, but this feature does not add client-side encryption. Anyone with S3 administrative access may be able to read the media. Configure bucket retention, versioning, encryption, lifecycle, and recovery separately, and test a restore before treating it as a backup.

## Optional screen assessment

Screen assessment is disabled by default. A parent may configure a read-only VNC address/password and an HTTPS OpenAI-compatible provider in the integration options. Automatic intervals have a five-minute minimum. Classic RFB password authentication is protocol-mandated DES and does not provide modern transport security; expose the VNC server only on a trusted private network or authenticated tunnel, never directly to the internet.

Provider output is constrained to a short structured safety assessment. Screenshots are not retained by this integration, but they are transmitted to the chosen provider under that provider's terms. Use a VNC server-side view-only password where supported.

## Privacy and retention

The Windows agent records foreground executable names and window titles. The Android agent records package and activity class names, not notification contents, message text, keystrokes, screenshots, or clipboard contents. Window titles and generated assessments may still contain private information.

Restrict Home Assistant access and configure an appropriate Recorder retention period. Review all example automations before use; notification service names and entity IDs are placeholders.

## Development

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 -m compileall -q custom_components/rowe_pc_blocker
./scripts/build-android-docker.sh
./scripts/build-release.sh 0.9.37
```

`tests/android_fake_stack.py` provides a synthetic HTTPS Home Assistant/S3
surface for disposable Android-emulator validation. Its companion preference
and OpenSSL fixtures contain test-only values. Debug APKs trust user-installed
test CAs for this purpose; non-debuggable release APKs do not.

The project is licensed under the [MIT License](LICENSE).
