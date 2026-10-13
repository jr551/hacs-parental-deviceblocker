# Changelog

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
