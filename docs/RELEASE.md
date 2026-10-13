# Release workflow

Pushing a tag matching `v*` (for example `v0.10.1`) runs [`.github/workflows/release.yml`](../.github/workflows/release.yml) and publishes a GitHub Release with:

| Asset | Description |
|-------|-------------|
| `parental_device_blocker-hacs-{version}.zip` | HACS integration (`custom_components/rowe_pc_blocker`) |
| `parental-device-blocker-android-{version}.apk` | Android client (`android/`) |
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
