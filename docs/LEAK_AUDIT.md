# Leak audit (2026-10-13)

Automated and manual review of the **full git history** (all fetched branches/tags) and the current tree. **No history rewrite was performed**; the owner decides whether to run the recommended `git filter-repo` steps below.

## Tools run

| Tool | Scope | Result |
|------|--------|--------|
| [gitleaks](https://github.com/gitleaks/gitleaks) v8.21.2 | All commits (`gitleaks detect --source .`) | 2 findings (test fixtures; see below) |
| `git log -p` + pattern search | Names, emails, LAN/Tailscale, hostnames, AWS keys | See § History |
| `tests/test_public_release.py` | Current tree forbidden substrings | Passes |

## Current tree (clean)

- No household hostnames (`*.rowe.lol`, `homeassistant.rowe.lol`, `s3.rowe.lol`), device keys, AWS keys, or child-specific Windows account names in tracked files.
- `tests/test_public_release.py` encodes forbidden literals to block reintroduction.
- `android/.../AppConfigTest.java` uses **generic** `http://192.168.1.20:8123` (RFC5737-style documentation example, not a household address).
- `docs/screenshots/` contains maintainer-supplied UI captures only (generated mock screenshots removed).
- `scripts/capture_ha_screenshots.py` and `scripts/ha_ws_helper.py` no longer embed demo Home Assistant credentials (use `PDB_HA_URL`, `PDB_HA_USERNAME`, `PDB_HA_PASSWORD` for local demos).

### gitleaks false positives (current tree)

| File | Note |
|------|------|
| `tests/test_api_security.py` | Synthetic API key strings for unit tests only |

Allowlisted via `.gitleaks.toml` for CI repeatability.

## History findings (requires owner decision)

### 1. Child names in tests — branch `origin/release/0.9.37` only

| Commit | Content |
|--------|---------|
| `d8c3ef1` | `tests/test_portal.py` uses example children **Matthew** and **Alex** |

**Not on `main`** as of audit. Still reachable via `origin/release/0.9.37`.

**Recommended rewrite** (if that branch must be deleted or scrubbed):

```bash
git filter-repo --force \
  --invert-paths \
  --path tests/test_portal.py \
  --refs refs/remotes/origin/release/0.9.37
# Or replace names in that file across all refs:
git filter-repo --force \
  --replace-text <(printf 'Matthew==>Child One\nAlex==>Child Two\nmatthew==>childone\n') \
  --refs refs/remotes/origin/release/0.9.37
```

Then delete or force-update the remote branch after owner review.

### 2. Author email in commit metadata

Many early commits use `johnrowe551@gmail.com` / `John Rowe` as **author** (public GitHub identity, not a deploy secret). This is normal for open-source attribution; rewrite only if the owner wants a different email on old commits:

```bash
git filter-repo --force --mailmap my-mailmap.txt
```

### 3. Demo Home Assistant password in agent branch commits

Commits on `cursorremove-s3-branding-protocol-5c12` briefly added `scripts/capture_ha_screenshots.py` with a local demo password (`DemoPass123!`) for VM screenshots. **Not a production credential**; removed from the tree. If that branch was never used against a real instance, no rotation needed.

### 4. Initial release scrub (`c80baa6`)

`test_public_release.py` was added explicitly to forbid household strings (`matthewpc`, `homeassistant.rowe.lol`, `7b0e3e640602`, etc.). No those strings remain in `main` tree.

## Live secrets — rotation checklist

| Finding | Rotate? |
|---------|---------|
| AWS / S3 keys in git | **None found** in history scan |
| Home Assistant long-lived tokens in git | **None found** |
| Device API keys in git | **None found** |
| Android release keystore in git | **None found** (signing stays in `~/.config/parental-device-blocker/`) |
| Public HA URL / device keys on Matthew’s PC | **Out of repo scope** — rotate via integration **Reconfigure** if ever committed or logged |

If `origin/release/0.9.37` was pushed from a household fork, treat device keys configured on that build as unchanged by this audit.

## Ongoing prevention

- Keep `tests/test_public_release.py` in CI.
- Run `gitleaks detect` in CI (see `.github/workflows/validate.yml`).
- Do not commit `deploy/home-assistant/rowe_pc_blocker.yaml`, screenshots with real UI, or `artifacts/`.
