# Security policy

## Supported versions

Security fixes are provided for the latest published release only. This project remains experimental and is not a tamper-proof security boundary. Android enforcement is an Accessibility friction control: it fails open, and it does not stop uninstall or a user turning Accessibility off.

## Threat model (device HTTP API)

Home Assistant exposes `/api/rowe_pc_blocker/{device_id}/…` on the same origin as the main UI. If your instance is reachable on the public internet, treat every device API key like a password for that child device.

**Assumptions**

- Attackers may know or guess your Home Assistant hostname and probe device IDs.
- Attackers may replay or brute-force credentials from arbitrary IPs.
- Child devices may be compromised; device keys on a locked PC can leak via the portal WebView.

**Goals**

- A device key authorizes only its own `device_id` path (no cross-device access).
- Failed authentication does not reveal whether a `device_id` exists (uniform `401 Unauthorized`).
- Parent override requires the parent PIN in addition to the device key; PIN guessing is rate-limited per device and per IP.
- Browser-only endpoints (`/portal/action`, `/parent_override`) require same-origin CSRF signals (`Origin` / `Sec-Fetch-Site` / `Referer`).
- Request bodies are size-bounded; malformed JSON is rejected without crashing Core.
- Repeated failures feed Home Assistant’s `ip_ban` integration (HTTP `401` handling) plus integration-level per-IP throttles.

**Non-goals**

- Hiding the existence of Home Assistant itself (TLS and network policy are still required).
- Preventing a child with a valid device key from calling agent endpoints (`/state`, `/activity`, `/extension`) — that is required for operation.

## Reporting a vulnerability

Please do not open a public issue for a vulnerability that could bypass enforcement, expose a device key, reveal child activity, or grant an unauthorized parent override.

Use GitHub's **Report a vulnerability** / private security advisory feature for this repository. Include the affected component and version, reproduction steps, expected impact, and any suggested mitigation. Do not include real household device keys, Home Assistant tokens, child activity, screenshots, or other personal data.

## Deployment guidance (internet-facing instances)

Prefer **not** exposing Home Assistant directly. Practical options:

1. **Tailscale** (or another mesh VPN) — agents and parents reach HA on a private address; no public `/api/rowe_pc_blocker` surface.
2. **Cloudflare Tunnel + Cloudflare Access** (or similar) — put an identity-aware policy in front of `https://your-host/*` and especially `/api/rowe_pc_blocker/*`.
3. **Reverse proxy allow-lists** — if agents have stable egress IPs, restrict `/api/rowe_pc_blocker/` at the proxy.

Always:

- Use **HTTPS** end-to-end. Device keys and parent PINs must not travel over plaintext HTTP on untrusted networks.
- Enable Home Assistant **IP banning** (`http` integration login threshold / `ip_bans.yaml`) — failed device auth raises `401` and counts toward bans.
- Use one **unique device API key** per device (generated keys are ≥128-bit). Rotate via **Configure → Reconfigure** after a suspected leak.
- Configure a **unique parent PIN** per device entry; no default PIN exists. Parent override is disabled until a PIN is set.
- Keep VNC on a trusted private network or authenticated tunnel. Classic RFB password authentication uses protocol-mandated DES and is not secure enough for an internet-exposed service, even with a view-only password.
- Keep Home Assistant and device agents updated.
- Use standard child accounts and retain a separate parent administrator recovery path.
- Test fail-open/fail-closed behavior appropriate to each platform before relying on enforcement.

## Portal `?key=` handling

The kiosk HTML entrypoint allows `?key=` once for WebView convenience. Responses set `Cache-Control: no-store` and `Referrer-Policy: no-referrer`. JavaScript clears the query string immediately; subsequent calls use the `X-Device-Blocker-Key` header. A short-lived **HttpOnly** portal session cookie (SameSite=Strict) is also set so reloads do not need the query string. Data and action endpoints do not accept `?key=`.

## Diagnostics and logging

Diagnostics downloads redact API keys, VNC passwords, provider keys, and parent PINs. API handlers do not log supplied keys or activity payloads.
