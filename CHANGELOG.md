# Changelog

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
