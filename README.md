<p align="center">
  <img src="brand/logo.png" alt="Parental Device Blocker" width="120" />
</p>

<h1 align="center">Parental Device Blocker</h1>

<p align="center">
  Home Assistant parental controls for Windows PCs and Android phones — block enforcement, activity telemetry, optional screen assessment, and a child-scoped points portal.
</p>

> [!WARNING]
> Experimental parental-control aid, not a tamper-proof security boundary. Supervise setup, keep a separate parent administrator account, and test emergency recovery before relying on enforcement.

## Features

Each configured device exposes Home Assistant entities for:

- requested and effective blocked state;
- an editable block message;
- agent connectivity and foreground application/window telemetry;
- optional passive Android location (cached fix only);
- optional read-only VNC screen assessment and bounded text-only reports;
- optional one-hour parent PIN override at the locked device;
- Windows points/chores portal when Family Chore Manager is installed.

Windows uses a `LocalSystem` service plus a session companion. Android uses Accessibility enforcement with deliberate fail-open behaviour during outages.

## Screenshots

![Device page in Home Assistant](docs/screenshots/device-page.png)

**Device page in Home Assistant** — Android Accessibility Agent controls, sensors, and activity.

Additional captures (config flow, options, dashboard) will be added under `docs/screenshots/` as they are ready.

## Install (HACS)

1. **HACS → Integrations → Custom repositories** — add `https://github.com/jr551/hacs-parental-deviceblocker` as an **Integration**.
2. Install **Parental Device Blocker** and restart Home Assistant.
3. **Settings → Devices & services → Add integration** → **Parental Device Blocker**.
4. Add one entry per PC or phone. Copy each generated **Device API key** only into that device's agent.

The internal domain remains `rowe_pc_blocker` for compatibility. Optional YAML import: `deploy/home-assistant/rowe_pc_blocker.yaml.example`.

### Parent PIN override

Open the device entry **Configure** options and set a parent PIN (at least four characters). Leave the field blank to keep the saved PIN. **Disable parent PIN override** clears it until a new PIN is set.

### Screen assessment (optional)

Enable in options with VNC host/password and an HTTPS OpenAI-compatible provider. Screenshots are not retained; only a short text assessment is stored.

## Windows agent

Build with .NET 8 or `./scripts/build-release.sh 0.10.0`. Install with `deploy/windows/Install-RowePcBlocker.ps1` (monitor-only first, then `Enable-UserInterface.ps1` and `Enable-Enforcement.ps1`). Emergency recovery: `Emergency-Unblock.ps1`. See the [Windows deploy checklist](docs/windows-deploy-checklist.md).

The service updates itself from the public GitHub release: at most once an hour it compares its own file version with the newest published release and, when a newer `ParentalDeviceBlocker-<version>-win-x64-setup.exe` (or `-win-x64.zip`) exists, downloads it into `%ProgramData%\RowePcBlocker\updates\`, verifies it against the release's `SHA256SUMS`, and only then runs the installer — or, when only the zip is published, the archive's `Upgrade-Installed-RowePcBlocker.ps1`. It refuses to update while the PC is blocked or a block is requested, and any failure is logged and retried on the next hourly check. Nothing is offered until the release is published.

The child-facing overlay also reports the phone. When the phone has an active chore challenge the PC says so full screen, names the child, and lets them keep working for five minutes before the notice becomes a hard kiosk; when the phone has not checked in for a day the PC freezes with **Parental control cannot be verified. Please see a parent.** A parent override suppresses both, and both clear as soon as the phone answers or checks in again.

Wire protocol (unchanged): [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## Android agent

Sideload APK from `./scripts/build-android-docker.sh`. See [android deploy checklist](docs/android-deploy-checklist.md).

On first launch the parent enters the child's name and the Home Assistant address, then taps **Set up this phone**. The phone asks Home Assistant to start a setup request and then waits, polling every five seconds for up to 30 minutes, while the screen says **Waiting for a parent to approve in Home Assistant…**. A parent approves or declines on the `sensor.rowe_pc_blocker_pending_setups` card (the approve/reject buttons appear with the request). On approval the phone stores the address, device id, and API key itself; a declined or expired request says so and nothing is saved. If Home Assistant cannot be reached, the screen says so and blocking stays off. Use HTTPS. Plain HTTP is accepted only for local names such as `http://192.168.x.x`, `http://homeassistant.local`, or a single-label LAN host. A local parent PIN, if one has already been set, is required before changing a phone that is already set up. Two wrong attempts lock that change for 15 minutes.

Each phone is its own integration entry, with its own device id and API key. Set **Child name** to the chore-manager child that phone belongs to (the same match the points portal already uses: the child name, or that name inside the device name). Buying an hour spends that child's Taskmate points. Nothing in the phone app is tied to one child.

The wake alarm is configured only on that device's integration options: on/off, time, weekdays (Monday–Friday until you change them), and alarm volume **0–100**. 100 is the phone's maximum alarm-stream volume, and 0 is silent. The phone reads `wake_alarm` on the existing once-a-minute state poll, caches the last schedule, and rings on the alarm stream at that volume. The person holding the phone can stop a ringing alarm. If Home Assistant cannot be reached, the block fails open and the last cached alarm stays scheduled.

Samsung Galaxy S20 (SM-G980F/G981B, One UI 2.5–5.x, Android 10–13), S20+ (SM-G986B), and Galaxy S22 (Android 13–14) are the documented targets, with targetSdk 35 and minSdk 29. The app installs as **Android Settings** with a gear icon so it does not advertise itself on the launcher; the package stays `lol.rowe.blocker`. The block screen lists the apps a parent allows in Home Assistant (`text.<device>_allowed_apps`), falling back to the built-in Calls/WhatsApp/Maps/MapMyWalk/Classroom/Clock/Camera set while that list is empty, and shows the child's other device ("Your PC is open — Roblox · seen 2 minutes ago"). It also names the app the child was blocked on and offers a **Request unblock** button that asks a parent to allow it (one request per app per ten minutes); the screen clears itself once that app appears in the allow-list. The phone reports its launchable apps (`sensor.<device>_installed_apps`) whenever that set changes, and refreshes a stale location cache at most once every 15 minutes. WhatsApp/Maps `getLaunchIntentForPackage` only resolves the current user/profile — clones in Samsung Secure Folder / Dual Messenger are isolated and will not be launched; the primary profile’s install is used. Test Secure Folder explicitly if you rely on it.

Home Assistant can also set a **chore challenge**: the phone locks with a phrase the child must type back and a photo they must take as evidence, and the lock beeps until it is answered. A parent override that Home Assistant grants, or that the phone's own parent PIN grants when Home Assistant refuses or cannot be reached, takes the lock down while it lasts. The wake alarm rings through silent mode and Do Not Disturb, and has a **Snooze 5 min** button next to **Stop alarm**.

The chore challenge is enforced like a block: an unanswered challenge covers disallowed apps through the same Accessibility path as a parent block, so Back, Home and the launcher cannot dismiss it, the allowed apps stay usable, and the phrase screen reappears on every poll until it is answered. The phone also keeps itself up to date — it checks the public release on its minute poll, verifies the download against the release's `.sha256` and against the app's own signing certificate, and shows an **Update needed** screen with an **Install** button that clears once the new version is running. With notification access granted (the setup screen offers the shortcut and says plainly what is collected), the phone records the day's notifications and posts the previous day's list to Home Assistant at local midnight, with a catch-up on the next poll. The foreground notification Android requires reads **Android Settings** / "Keeping this device up to date." on a silent, minimum-importance channel.

## Compatibility identifiers (legacy names)

Shipped agents depend on these **technical** identifiers. User-facing branding is *Parental Device Blocker*; do not rename these in releases without a coordinated client migration.

| Token | Value |
| --- | --- |
| HA domain / folder | `rowe_pc_blocker` |
| API prefix | `/api/rowe_pc_blocker/` |
| Preferred auth header | `X-Device-Blocker-Key` |
| Legacy auth header | `X-Rowe-Key` |
| Windows service / binaries | `RowePcBlocker`, `RowePcActivity` |
| Android application ID | `lol.rowe.blocker` |

## Development

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 -m compileall -q custom_components/rowe_pc_blocker

```

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

Licensed under the [MIT License](LICENSE).
