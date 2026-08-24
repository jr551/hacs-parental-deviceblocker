#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
image_name="parental-device-blocker-android:local"
artifact_name="ParentalDeviceBlocker-0.4.5-debug.apk"
legacy_keystore_dir="${XDG_CONFIG_HOME:-$HOME/.config}/rowe-pc-blocker"
default_keystore_dir="${XDG_CONFIG_HOME:-$HOME/.config}/parental-device-blocker"
if [[ "${PARENTAL_DEVICE_BLOCKER_REUSE_LEGACY_KEY:-0}" == "1" ]]; then
  default_keystore_dir="$legacy_keystore_dir"
fi
keystore_dir="${PARENTAL_DEVICE_BLOCKER_KEYSTORE_DIR:-${ROWE_ANDROID_KEYSTORE_DIR:-$default_keystore_dir}}"
keystore_path="$keystore_dir/android-debug.keystore"

if [[ -n "${ROWE_DOCKER_HOST:-}" ]]; then
  docker_command=(docker --host "$ROWE_DOCKER_HOST")
elif [[ -S "$HOME/.docker/run/docker.sock" ]]; then
  docker_command=(docker --host "unix://$HOME/.docker/run/docker.sock")
else
  docker_command=(docker)
fi

if [[ ! -f "$keystore_path" ]]; then
  mkdir -p "$keystore_dir"
  chmod 700 "$keystore_dir"
  "${docker_command[@]}" run --rm --platform linux/amd64 \
    --user "$(id -u):$(id -g)" \
    --volume "$keystore_dir:/keys" \
    docker.io/library/gradle:8.10.2-jdk17 \
    keytool -genkeypair -noprompt \
      -keystore /keys/android-debug.keystore \
      -storepass android \
      -alias androiddebugkey \
      -keypass android \
      -dname "CN=Parental Device Blocker Test,O=Local Test Build" \
      -keyalg RSA -keysize 2048 -validity 10000
  chmod 600 "$keystore_path"
  echo "Created protected Android signing key at $keystore_path"
fi

signing_key_id="$(shasum -a 256 "$keystore_path" | awk '{print substr($1, 1, 16)}')"
"${docker_command[@]}" build --platform linux/amd64 --quiet \
  --build-arg "SIGNING_KEY_ID=$signing_key_id" \
  --secret "id=parental_device_blocker_keystore,src=$keystore_path" \
  -t "$image_name" "$repo_root/android"
container_id="$("${docker_command[@]}" create "$image_name")"
mkdir -p "$repo_root/artifacts/android"
"${docker_command[@]}" cp "$container_id:/workspace/app/build/outputs/apk/debug/app-debug.apk" \
  "$repo_root/artifacts/android/$artifact_name"
"${docker_command[@]}" rm "$container_id" >/dev/null

shasum -a 256 "$repo_root/artifacts/android/$artifact_name"
