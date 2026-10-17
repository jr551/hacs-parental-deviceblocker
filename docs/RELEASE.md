# Release workflow

Pushing a tag matching `v*` (for example `v0.10.1`) runs [`.github/workflows/release.yml`](../.github/workflows/release.yml) and publishes a GitHub Release with:

| Asset | Description |
|-------|-------------|
| `parental_device_blocker-hacs-{version}.zip` | HACS integration (`custom_components/rowe_pc_blocker`) |
| `ParentalDeviceBlocker-{apk-version}.apk` and `.apk.sha256` | Android client (`android/`). The version is the app's own `versionName` from `android/app/build.gradle`, not the tag: the in-app updater matches that name and verifies the download against the `.sha256` sibling before it will install it. |
| `ParentalDeviceBlocker-{version}-win-x64-setup.exe` | Windows installer (Inno Setup over `scripts/build-release.sh` publish output) |
| `ParentalDeviceBlocker-{version}-win-x64.zip` | Portable Windows payload zip |
| `SHA256SUMS` | Checksums for all of the above |

`hacs.json` sets `zip_release` / `filename` so HACS can install from the release zip.

## Optional signing secrets

When secrets are unset, CI produces **unsigned** Android and Windows artifacts.

| Secret | Purpose |
|--------|---------|
| `PDB_ANDROID_KEYSTORE_BASE64` | Base64-encoded release keystore (same material as `~/.config/parental-device-blocker/android-release.keystore`) |
| `PDB_ANDROID_STORE_PASSWORD` | Keystore password |
| `PDB_ANDROID_KEY_ALIAS` | Key alias (for example `parental-device-blocker`) |
| `PDB_ANDROID_KEY_PASSWORD` | Key password |
| `PDB_WINDOWS_CERT_BASE64` | Base64-encoded code-signing PFX |
| `PDB_WINDOWS_CERT_PASSWORD` | PFX password |

Windows agent wire protocol is unchanged; only packaging and signing run in CI.

## Windows self-update

An installed Windows service updates itself from the published release, so a
release must be **published** (not just tagged) before any PC is offered it:

- It reads `releases/latest`, matches `ParentalDeviceBlocker-<version>-win-x64-setup.exe`
  and `ParentalDeviceBlocker-<version>-win-x64.zip`, and acts only when that
  version is strictly newer than its own file version.
- The download is verified against the release's `SHA256SUMS` asset. A missing
  or mismatched checksum aborts the update, so a release without `SHA256SUMS`,
  or with an asset missing from it, is never installed.
- The installer is run silently (`/VERYSILENT /SUPPRESSMSGBOXES /NORESTART`)
  and then the `Install-RowePcBlocker.ps1` it unpacks, with the live
  configuration. When only the zip is published, the archive's
  `Upgrade-Installed-RowePcBlocker.ps1` performs the swap.
- Checks happen at most once an hour, never while the PC is blocked or a block
  is requested, and their output is appended to
  `%ProgramData%\RowePcBlocker\updates\update-<version>.log`.
