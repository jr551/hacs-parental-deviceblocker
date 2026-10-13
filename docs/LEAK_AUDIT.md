# Leak audit (2026-10-13)

Automated and manual review of the **full git history** (all fetched branches/tags) and the current tree. **No history rewrite was performed**; the owner decides whether to run the recommended `git filter-repo` steps below.

## Tools run

| Tool | Scope | Result |
|------|--------|--------|
| [gitleaks](https://github.com/gitleaks/gitleaks) v8.21.2 | All commits (`gitleaks detect --source .`) | Clean with `.gitleaks.toml` test allowlist |
| `git log -p` + pattern search | Names, emails, LAN/Tailscale, hostnames, AWS keys | See § History |
| `tests/test_public_release.py` | Current tree forbidden substrings | Passes |

## Current tree (clean)

- No household hostnames (`*.rowe.lol`, private HA hostnames, S3 backup hosts), device keys, AWS keys, or child-specific Windows account names in tracked files.
- `tests/test_public_release.py` encodes forbidden literals to block reintroduction.
- `android/.../AppConfigTest.java` uses **generic** `http://192.168.1.20:8123` (RFC5737-style documentation example, not a household address).
- `docs/screenshots/` contains maintainer-supplied UI captures only (header/breadcrumb cropped; generated mocks removed).
- `scripts/capture_ha_screenshots.py` and `scripts/ha_ws_helper.py` use environment variables for local demo credentials (`PDB_HA_*`).

### gitleaks false positives (current tree)

| File | Note |
|------|------|
| `tests/test_api_security.py` | Synthetic API key strings for unit tests only |

Allowlisted via `.gitleaks.toml` for CI repeatability.

## History findings (requires owner decision)

### 1. Personal child names in tests — branch `origin/release/0.9.37` only

| Commit | Content |
|--------|---------|
| `d8c3ef1` | `tests/test_portal.py` used real child names in examples (scrubbed on `main` to `kid1` / `kid2` style placeholders) |

**Not on `main`** as of audit. Still reachable via `origin/release/0.9.37`.

**Recommended rewrite** (if that branch must be deleted or scrubbed):

```bash
git filter-repo --force \
  --invert-paths \
  --path tests/test_portal.py \
  --refs refs/remotes/origin/release/0.9.37
```

Then delete or force-update the remote branch after owner review.

### 2. Author email in commit metadata

Early commits may use a personal GitHub author email in **metadata** (public attribution, not a deploy secret). Rewrite only if the owner wants a different email on old commits:

```bash
git filter-repo --force --mailmap my-mailmap.txt
```

### 3. Demo Home Assistant password in agent branch commits

Commits on feature branches briefly added `scripts/capture_ha_screenshots.py` with a local demo password for VM screenshots. **Not a production credential**; removed from the tree. If that branch was never used against a real instance, no rotation needed.

### 4. Initial release scrub (`c80baa6`)

`test_public_release.py` was added explicitly to forbid household strings (legacy Windows account slug, private hostnames, sample device key fragments, etc.). Those strings do not remain in the `main` tree.

## Live secrets — rotation checklist

| Finding | Rotate? |
|---------|---------|
| AWS / S3 keys in git | **None found** in history scan |
| Home Assistant long-lived tokens in git | **None found** |
| Device API keys in git | **None found** |
| Android release keystore in git | **None found** (signing stays in `~/.config/parental-device-blocker/`) |
| Device keys on a household PC | **Out of repo scope** — rotate via integration **Reconfigure** if ever committed or logged |

If `origin/release/0.9.37` was pushed from a household fork, treat device keys configured on that build as unchanged by this audit.

## Legacy protocol names (intentionally unchanged)

These identifiers remain for **wire and deployment compatibility** with shipped Windows/Android agents:

- Home Assistant domain / folder: `rowe_pc_blocker`
- API path prefix: `/api/rowe_pc_blocker/`
- Legacy auth header: `X-Rowe-Key`
- Windows projects/services: `RowePcBlocker`, `RowePcActivity`
- Android package: `lol.rowe.blocker`

User-facing branding is **Parental Device Blocker**; `rowe_*` names are legacy technical identifiers only.

## Ongoing prevention

- Keep `tests/test_public_release.py` in CI.
- Run `gitleaks detect` in CI (see `.github/workflows/validate.yml`).
- Do not commit `deploy/home-assistant/rowe_pc_blocker.yaml`, screenshots with real UI, or `artifacts/`.
