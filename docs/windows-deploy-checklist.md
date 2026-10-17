# Windows deploy checklist

Use this before installing Parental Device Blocker on a child's PC. Windows
\*\*0.13.0\*\* belongs with Home Assistant **0.13.0** or newer. The release assets
are `ParentalDeviceBlocker-{version}-win-x64-setup.exe` (installer) and
`ParentalDeviceBlocker-{version}-win-x64.zip` (portable payload); verify
whichever you use against the release's `SHA256SUMS` first. The HACS zip does
not contain either.

This build is supervised friction. It is not tamper-proof: the child can kill
the kiosk process, and the SYSTEM watchdog restarts it within a minute. It
fails open when Home Assistant cannot be reached, and the service keeps the
last known state during an outage.

## Pre-flight

- [ ] The child's account is a standard local or Microsoft-linked account, not an administrator. The installer refuses an administrator.
- [ ] The PC can reach the Home Assistant URL on the child's network.
- [ ] Create one integration entry per PC. Do not share device ids or API keys.
- [ ] Set **Child name** to that PC's chore-manager child. The `family` block matches the child's phone on this name, so the phone notices only work when both entries agree.
- [ ] Store the device API key in a parent password manager.

## Install

- [ ] Run `Install-RowePcBlocker.ps1` from an elevated PowerShell window with the Home Assistant URL, device id, API key, and child username. It installs **monitor only** unless `-EnableEnforcement` is passed.
- [ ] Confirm the service is running (`Get-Service RowePcBlocker`) and that `%ProgramData%\RowePcBlocker\appsettings.Local.json` holds the expected values.
- [ ] Within a minute, Home Assistant shows the PC agent online and foreground app updates while unblocked.
- [ ] `Enable-UserInterface.ps1` then `Enable-Enforcement.ps1`, one at a time, checking the kiosk appears and clears between them.
- [ ] Supervised block: the block screen appears full screen, the points portal loads, and Alt+Tab/Ctrl+Esc do not dismiss it.
- [ ] Emergency recovery: `Emergency-Unblock.ps1 -ChildUsername <child>` re-enables the account and disables the service, the kiosk task, and the watchdog.

## Phone notices

- [ ] With the PC unblocked, send a challenge to the child's phone (`rowe_pc_blocker.send_challenge`). The PC shows **Waiting for something on <child>'s phone**, the phrase, and a countdown.
- [ ] **Keep working for now** dismisses the notice and the PC stays usable; the notice does not come back until the grace lapses.
- [ ] Restart `RowePcActivity` (or reboot) during the grace: the countdown continues from where it was, because the service records the first sighting under `%ProgramData%\RowePcBlocker\challenge-grace.json`.
- [ ] After five minutes the notice becomes a hard kiosk that cannot be dismissed. Accepting the challenge on the phone, or `rowe_pc_blocker.clear_challenge`, clears it.
- [ ] A parent override (`extension_until` in the future) suppresses the notice, exactly as it suppresses the block screen.
- [ ] Stop the phone agent (or turn the phone off) for more than 24 hours: the PC shows **Phone app tamper detected** with a fifteen-minute countdown, then shuts down. It cannot be dismissed, and restarting the PC does not reset the countdown because it is measured from the phone's last check-in.
- [ ] With a second monitor attached, the freeze and the hard challenge kiosk cover every display, not just the primary one.
- [ ] Start the phone agent again: the freeze clears on the next poll.
- [ ] A child with no phone entry, or a phone that has never checked in (`last_seen` null), never freezes the PC.

## Self-update

- [ ] Publish a release whose version is newer than the installed one. Nothing is offered until the release is published.
- [ ] Within an hour the service downloads the asset into `%ProgramData%\RowePcBlocker\updates\`, verifies it against `SHA256SUMS`, and runs the installer or the archive's `Upgrade-Installed-RowePcBlocker.ps1`.
- [ ] The service restarts with the new version; the Application event log shows `Parental Device Blocker <version> starting`, and `%ProgramData%\RowePcBlocker\updates\update-<version>.log` holds the swap output.
- [ ] Block the PC, then publish a newer release: no update is attempted while blocked or block-requested.
- [ ] Corrupt the downloaded file (or remove its `SHA256SUMS` line) and confirm the update is refused and logged rather than installed.
- [ ] Confirm blocking and the state poll are unaffected by a failed update: the kiosk still appears on the next block.

## Parent operations

- [ ] Record the Home Assistant parent-override PIN where the children cannot read it.
- [ ] Keep one PC on monitor-only for a day before enabling enforcement on the rest.
