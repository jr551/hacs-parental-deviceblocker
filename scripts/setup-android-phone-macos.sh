#!/usr/bin/env bash
# Set up a USB-connected Samsung phone as a Parental Device Blocker device.
#
# Walks through every step, does the parts that can be automated over adb, and
# stops with clear instructions for the parts Android only allows by hand.
#
#   scripts/setup-android-phone-macos.sh
#   scripts/setup-android-phone-macos.sh --device-id child-phone --ha-url https://homeassistant.example
#   HA_DEVICE_KEY=... scripts/setup-android-phone-macos.sh --device-id child-phone
#
# Options:
#   --device-id ID    device id you created in Home Assistant (default: prompted)
#   --ha-url URL      Home Assistant base URL the phone should talk to
#   --apk PATH        APK to install (default: newest under artifacts/android/)
#   --serial SERIAL   target a specific adb serial when several are attached
#   --no-install      skip installing the APK (configure an existing install)
#   --keep-awake      dedicated-device tweaks: never sleep while charging
set -uo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
package="lol.rowe.blocker.debug"          # debug builds carry the .debug suffix
service="$package/lol.rowe.blocker.BlockAccessibilityService"
prefs="rowe_blocker"

device_id=""; ha_url=""; apk=""; serial=""; do_install=1; keep_awake=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --device-id) device_id="${2:-}"; shift 2 ;;
    --ha-url) ha_url="${2:-}"; shift 2 ;;
    --apk) apk="${2:-}"; shift 2 ;;
    --serial) serial="${2:-}"; shift 2 ;;
    --no-install) do_install=0; shift ;;
    --keep-awake) keep_awake=1; shift ;;
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 64 ;;
  esac
done

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '   \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '   \033[33m!\033[0m %s\n' "$*"; }
todo() { printf '   \033[36m→\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31mStopped:\033[0m %s\n' "$*" >&2; exit 1; }
pause() { printf '\n   Press Return when done… '; read -r _ </dev/tty; }

adb_cmd() { if [[ -n "$serial" ]]; then adb -s "$serial" "$@"; else adb "$@"; fi; }

step "1/8  Checking the Mac has adb"
if ! command -v adb >/dev/null 2>&1; then
  die "adb not found. Install it with:  brew install --cask android-platform-tools"
fi
ok "adb $(adb version | head -1 | awk '{print $NF}')"

step "2/8  Finding the phone"
cat <<'PHONE'
   On the phone, this must already be true (Samsung path):
     • Settings → About phone → Software information → tap "Build number" 7×
     • Settings → Developer options → USB debugging = ON
     • Plug into this Mac with a data-capable USB cable
     • Accept the "Allow USB debugging?" prompt (tick "Always allow")
PHONE
adb start-server >/dev/null 2>&1
devices="$(adb devices | awk 'NR>1 && NF==2 {print $1, $2}')"
if [[ -z "$devices" ]]; then die "No device seen by adb. Re-plug the cable and accept the prompt on the phone."; fi
if grep -q unauthorized <<<"$devices"; then die "Phone is 'unauthorized' — unlock it and accept the USB debugging prompt."; fi
count="$(wc -l <<<"$devices" | tr -d ' ')"
if [[ "$count" -gt 1 && -z "$serial" ]]; then
  echo "$devices"; die "Several devices attached. Re-run with --serial <serial>."
fi
model="$(adb_cmd shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
brand="$(adb_cmd shell getprop ro.product.brand 2>/dev/null | tr -d '\r')"
release="$(adb_cmd shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
ok "$brand $model on Android $release"
[[ "$(printf '%s' "$brand" | tr '[:upper:]' '[:lower:]')" == "samsung" ]] || warn "Not a Samsung — the manual steps below may sit in different menus."

step "3/8  Installing the app"
if [[ $do_install -eq 1 ]]; then
  if [[ -z "$apk" ]]; then
    apk="$(ls -t "$repo_root"/artifacts/android/*.apk 2>/dev/null | head -1 || true)"
  fi
  [[ -n "$apk" && -f "$apk" ]] || die "No APK found. Build one with scripts/build-android-docker.sh or pass --apk PATH."
  ok "using $(basename "$apk")"
  if ! adb_cmd install -r -g "$apk" 2>&1 | tail -2 | grep -qi success; then
    warn "Reinstall failed (usually a signature clash with an older build)."
    todo "Uninstall on the phone, or run: adb uninstall $package"
    die "Install did not succeed."
  fi
  ok "installed $package"
else
  ok "skipped (--no-install)"
fi

step "4/8  Home Assistant device"
if [[ -z "$device_id" || -z "$ha_url" ]]; then
  cat <<'HA'
   In Home Assistant, create the phone's device entry first:
     • Settings → Devices & services → Add integration → "Parental Device Blocker"
     • Device id: short and lowercase, e.g. child-phone
     • Device type: android
     • Copy the pre-filled API key — you need it in a moment
HA
  pause
fi
[[ -n "$device_id" ]] || { printf '   Device id: '; read -r device_id </dev/tty; }
[[ -n "$ha_url" ]] || { printf '   Home Assistant URL (e.g. https://homeassistant.example): '; read -r ha_url </dev/tty; }
device_key="${HA_DEVICE_KEY:-}"
if [[ -z "$device_key" ]]; then
  printf '   API key (hidden): '; read -rs device_key </dev/tty; printf '\n'
fi
[[ -n "$device_id" && -n "$ha_url" && -n "$device_key" ]] || die "Device id, URL and API key are all required."
ha_url="${ha_url%/}"

step "5/8  Writing the app configuration"
# The debug build is debuggable, so its own sandbox is writable via run-as. This
# saves typing a long key on a phone keyboard; the config screen is PIN-gated
# anyway. If run-as is refused, fall back to typing it in the app.
prefs_xml=$(cat <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="ha_url">$ha_url</string>
    <string name="device_id">$device_id</string>
    <string name="device_key">$device_key</string>
</map>
XML
)
encoded="$(printf '%s' "$prefs_xml" | base64)"
if adb_cmd shell "run-as $package sh -c 'mkdir -p /data/data/$package/shared_prefs && echo $encoded | base64 -d > /data/data/$package/shared_prefs/$prefs.xml'" >/dev/null 2>&1 &&
   adb_cmd shell "run-as $package cat /data/data/$package/shared_prefs/$prefs.xml" 2>/dev/null | grep -q "$device_id"; then
  ok "configuration written directly to the app"
  configured_automatically=1
else
  warn "Could not write the app's private data over adb (normal on some builds)."
  configured_automatically=0
fi

step "6/8  Accessibility service (this is what enforces blocking)"
current="$(adb_cmd shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
[[ "$current" == "null" ]] && current=""
if [[ ":$current:" == *":$service:"* ]]; then
  ok "already enabled"
else
  merged="$service"; [[ -n "$current" ]] && merged="$current:$service"
  adb_cmd shell settings put secure enabled_accessibility_services "$merged" >/dev/null 2>&1
  adb_cmd shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
  verify="$(adb_cmd shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
  if [[ ":$verify:" == *":$service:"* ]]; then
    ok "enabled over adb (existing services preserved)"
  else
    warn "Android refused the adb change — enable it by hand:"
    todo "Settings → Accessibility → Installed apps → Parental Device Blocker → On"
    adb_cmd shell am start -a android.settings.ACCESSIBILITY_SETTINGS >/dev/null 2>&1
    pause
  fi
fi

step "7/8  Battery — the usual reason enforcement dies silently"
adb_cmd shell dumpsys deviceidle whitelist "+$package" >/dev/null 2>&1 &&
  ok "added to the doze whitelist" || warn "Could not set the doze whitelist over adb."
cat <<SAMSUNG
   Samsung's own battery manager still needs two manual changes:
     • Settings → Apps → Parental Device Blocker → Battery → Unrestricted
     • Settings → Battery → Background usage limits →
         make sure the app is NOT in "Sleeping"/"Deep sleeping" apps
SAMSUNG
adb_cmd shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$package" >/dev/null 2>&1
pause
if [[ $keep_awake -eq 1 ]]; then
  adb_cmd shell settings put global stay_on_while_plugged_in 7 >/dev/null 2>&1
  adb_cmd shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1
  ok "dedicated-device mode: stays awake while charging"
fi

step "8/8  Starting the app and checking in"
adb_cmd shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4
if [[ ${configured_automatically:-0} -eq 0 ]]; then
  cat <<MANUAL
   Finish configuration on the phone (the app is open):
     • Create and confirm a unique parent PIN; there is no default PIN
     • Home Assistant URL: $ha_url
     • Device id: $device_id
     • Device key: the API key from Home Assistant
     • Tap Save
     • If S3 backup is enabled for this entry, tap the optional media-backup
       button and grant full photo/video access; uploads wait for external power
       and the initial upload also waits for Wi-Fi
MANUAL
  pause
else
  cat <<MANUAL
   Finish configuration on the phone (the app is open):
     • Create and confirm a unique parent PIN; there is no default PIN
     • The Home Assistant URL, device id, and key were provisioned securely
     • If S3 backup is enabled, grant full photo/video access from the optional
       media-backup button; uploads wait for external power and the initial
       upload also waits for Wi-Fi
MANUAL
  pause
fi
printf '   Asking Home Assistant whether the phone has checked in… '
state="$(curl -sS -m 10 -H "X-Device-Blocker-Key: $device_key" "$ha_url/api/rowe_pc_blocker/$device_id/state" 2>/dev/null || true)"
if grep -q '"device_id"' <<<"$state"; then
  printf '\n'; ok "Home Assistant answers for $device_id"
  grep -o '"blocked":[a-z]*' <<<"$state" | sed 's/^/   /'
else
  printf '\n'; warn "No valid answer yet. Check the URL, key, and that the phone is on the network."
fi

cat <<DONE

Setup finished. Worth confirming in Home Assistant:
  • the device's "Agent online" sensor turns on within a minute
  • toggling its Blocked switch shows the block screen, then turn it back off
Reminders:
  • The config screen is protected by the parent PIN you just created.
  • Samsung battery settings are the most common cause of blocking stopping
    days later; re-check them if enforcement ever goes quiet.
DONE
