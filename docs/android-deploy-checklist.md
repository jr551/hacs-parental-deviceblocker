# Android deploy checklist

Use this before installing Parental Device Blocker on a child's phone. Android **0.4.10** belongs with Home Assistant **0.10.0** or newer. The HACS zip does not contain the APK. The release asset to install is the non-debug package:

- `ParentalDeviceBlocker-0.4.10.apk`
- `ParentalDeviceBlocker-0.4.10.apk.sha256`

Verify the checksum before install. Do not install `*-debug.apk` on a phone a child uses.

This build is supervised friction. It is not Device Owner, it does not block uninstall, and it fails open when Home Assistant or the network cannot confirm a block. Pilot one phone, including an overnight idle and a few days of normal use, before repeating the checklist on a second phone.

Documented Samsung targets: Galaxy S20 (SM-G980F/G981B), Galaxy S20+ (SM-G986B), and Galaxy S22. The S22 needs its own soak. One UI battery limits are often stricter there than on the S20 notes.

## Pre-flight

- [ ] Confirm the live Home Assistant `rowe_pc_blocker` version is **0.10.0** or newer.
- [ ] Create one integration entry per phone. Do not share device ids or API keys.
- [ ] Set **Child name** to that phone's chore-manager child. Points hours and the wake alarm follow this entry only.
- [ ] On that entry's options, set the wake alarm: on/off, time, weekdays (Monday–Friday by default), and volume 0–100 (100 is the phone's maximum alarm-stream volume). The phone picks this up on its state poll. Stopping a ringing alarm is done on the phone; changing the schedule is Home Assistant only.
- [ ] Device type: Android phone. Store each API key in a parent password manager.
- [ ] Set a distinct Home Assistant parent-override PIN, or leave the override disabled on purpose. This PIN is not the phone's local configuration PIN.
- [ ] Choose an HTTPS Home Assistant URL that the phone can reach on Wi-Fi and on mobile data. Plain HTTP is accepted only for a local address.
- [ ] Server-mediated S3 backup was removed in integration 0.10.0; skip media-backup planning unless you use a separate tool.
- [ ] Download the **0.4.10** release APK and check its SHA-256 against `ParentalDeviceBlocker-0.4.10.apk.sha256`.

## Per phone

- [ ] The parent Google account is the device owner. The child is not a device admin.
- [ ] Install `ParentalDeviceBlocker-0.4.10.apk` (package `lol.rowe.blocker`, not `lol.rowe.blocker.debug`).
- [ ] Create a local configuration PIN of at least four digits. There is no default and no recovery PIN. Two wrong attempts lock the setup screen for 15 minutes.
- [ ] Save the Home Assistant URL, device id, and device key. The key is sealed in the Android Keystore. The setup screen says so when sealing fails.
- [ ] Turn Accessibility on for Parental Device Blocker. Allow restricted settings if Android asks.
- [ ] Tap **Allow unrestricted battery**.
- [ ] Samsung, in addition to that prompt:
  - Settings → Apps → Parental Device Blocker → Battery → **Unrestricted**
  - Settings → Battery and device care → Battery → Background usage limits
  - Remove the app from **Sleeping apps** and **Deep sleeping apps**
  - Turn off **Put unused apps to sleep** for this app, or add it to Never sleeping apps
  - Re-check these after an One UI update
- [ ] Xiaomi/MIUI only: enable Autostart and background pop-up windows. Stock Samsung does not have that menu.
- [ ] Grant location only if a passive last-known fix is wanted.
- [ ] Turn **off** Developer options and USB debugging. A release build cannot be configured with `adb run-as`.
- [ ] Within about a minute, Home Assistant shows Agent online and foreground app updates while unblocked. Enforcement status reads `monitoring`.
- [ ] Supervised block: block screen appears, WhatsApp works, Google Maps works, the dialler works, and Back/Home do not dismiss a hard block.
- [ ] Secure Folder / Dual Messenger clones are not the apps the allow-list launches. Test them if anyone uses them.
- [ ] Fail-open proof: airplane mode or stop Home Assistant during a block. The phone unlocks. Enforcement status becomes `fail_open` when the phone can still reach Home Assistant; otherwise Agent online turns off and you treat the phone as unblocked with no fresh status. The `rowe_pc_blocker_enforcement_degraded` event fires only when Home Assistant receives a status report while a block was requested.
- [ ] Turn Accessibility off briefly. Enforcement status becomes `accessibility_disabled` while the watchdog is alive. Turn it back on before leaving the phone.
- [ ] Next morning: Accessibility is still on, the app is not in a sleep list, and the agent is still checking in.
- [ ] After a few days with no silent battery kill, repeat for the next phone.

## Parent operations

- [ ] Record both local PINs and both Home Assistant override PINs where the children cannot read them.
- [ ] Emergency unlock paths: turn the Home Assistant block switch off, use the parent override on the block screen, or disable Accessibility / uninstall. Uninstall is still possible. Do not describe this build as uninstall-proof.
- [ ] If the network is unreliable, do not use the phone block as the only bedtime control. Keep the PC and socket automations that already exist.
- [ ] Example alert: `deploy/home-assistant/rowe_pc_blocker_android_enforcement.yaml`. Replace the placeholder entity ids before use.

## Explicitly out of scope

- Device Owner / MDM uninstall protection. That needs a separate provisioning design and a factory-reset-aware rollout.
- Installing this APK onto a phone without a parent present for the checklist above.
