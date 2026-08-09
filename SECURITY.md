# Security policy

## Supported versions

Security fixes are provided for the latest published release only. This project remains experimental and is not a tamper-proof security boundary.

## Reporting a vulnerability

Please do not open a public issue for a vulnerability that could bypass enforcement, expose a device key, reveal child activity, or grant an unauthorized parent override.

Use GitHub's **Report a vulnerability** / private security advisory feature for this repository. Include the affected component and version, reproduction steps, expected impact, and any suggested mitigation. Do not include real household device keys, Home Assistant tokens, child activity, screenshots, or other personal data.

## Deployment guidance

- Use one unique device key per device.
- Configure a unique parent PIN; no default PIN exists.
- Use a dedicated write-only S3 key scoped to one device prefix. S3 credentials
  stay in Home Assistant; Android receives only short-lived, size-bound PUT URLs.
- Prefer HTTPS Home Assistant URLs.
- Keep Home Assistant and device agents updated.
- Use standard child accounts and retain a separate parent administrator recovery path.
- Test fail-open/fail-closed behavior appropriate to each platform before relying on enforcement.
