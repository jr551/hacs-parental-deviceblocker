#!/usr/bin/env bash
# Build a signed Android package.
#
#   ./scripts/build-android-docker.sh            # non-debuggable release APK
#   ./scripts/build-android-docker.sh debug      # debug APK for emulator CA tests
#
# Release signing material is created under ~/.config/parental-device-blocker/
# and is never copied into the image or the repository. Docker is used when it
# is available; otherwise the script uses a local Android SDK (ANDROID_HOME)
# and Gradle.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
mode="${1:-release}"
if [[ "$mode" != "release" && "$mode" != "debug" ]]; then
  echo "Usage: $0 [release|debug]" >&2
  exit 64
fi

version_name="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' "$repo_root/android/app/build.gradle" | head -1)"
if [[ -z "$version_name" ]]; then
  echo "Could not read versionName from android/app/build.gradle" >&2
  exit 1
fi

image_name="parental-device-blocker-android:local"
legacy_keystore_dir="${XDG_CONFIG_HOME:-$HOME/.config}/rowe-pc-blocker"
default_keystore_dir="${XDG_CONFIG_HOME:-$HOME/.config}/parental-device-blocker"
if [[ "${PARENTAL_DEVICE_BLOCKER_REUSE_LEGACY_KEY:-0}" == "1" ]]; then
  default_keystore_dir="$legacy_keystore_dir"
fi
keystore_dir="${PARENTAL_DEVICE_BLOCKER_KEYSTORE_DIR:-${ROWE_ANDROID_KEYSTORE_DIR:-$default_keystore_dir}}"

if [[ "$mode" == "debug" ]]; then
  keystore_path="$keystore_dir/android-debug.keystore"
  artifact_name="ParentalDeviceBlocker-${version_name}-debug.apk"
  apk_inside="/workspace/app/build/outputs/apk/debug/app-debug.apk"
  docker_target="debug"
else
  keystore_path="$keystore_dir/android-release.keystore"
  signing_path="$keystore_dir/android-release.signing"
  artifact_name="ParentalDeviceBlocker-${version_name}.apk"
  apk_inside="/workspace/app/build/outputs/apk/release/app-release.apk"
  docker_target="release"
fi

if [[ -n "${ROWE_DOCKER_HOST:-}" ]]; then
  docker_command=(docker --host "$ROWE_DOCKER_HOST")
elif [[ -S "$HOME/.docker/run/docker.sock" ]]; then
  docker_command=(docker --host "unix://$HOME/.docker/run/docker.sock")
else
  docker_command=(docker)
fi

use_docker=0
if command -v docker >/dev/null 2>&1 && "${docker_command[@]}" info >/dev/null 2>&1; then
  use_docker=1
fi

mkdir -p "$keystore_dir"
chmod 700 "$keystore_dir"

if [[ "$mode" == "debug" ]]; then
  if [[ ! -f "$keystore_path" ]]; then
    if [[ "$use_docker" == "1" ]]; then
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
    else
      keytool -genkeypair -noprompt \
        -keystore "$keystore_path" \
        -storepass android \
        -alias androiddebugkey \
        -keypass android \
        -dname "CN=Parental Device Blocker Test,O=Local Test Build" \
        -keyalg RSA -keysize 2048 -validity 10000
    fi
    chmod 600 "$keystore_path"
    echo "Created protected Android debug signing key at $keystore_path"
  fi
else
  if [[ ! -f "$keystore_path" ]]; then
    # PKCS12 keystores use one password for the store and the key.
    store_pass="$(openssl rand -hex 24)"
    key_pass="$store_pass"
    umask 077
    cat > "$signing_path" <<EOF
PDB_STORE_PASSWORD=$store_pass
PDB_KEY_PASSWORD=$key_pass
PDB_KEY_ALIAS=parental-device-blocker
EOF
    chmod 600 "$signing_path"
    if command -v keytool >/dev/null 2>&1; then
      keytool -genkeypair -noprompt \
        -keystore "$keystore_path" \
        -storepass "$store_pass" \
        -alias parental-device-blocker \
        -keypass "$key_pass" \
        -dname "CN=Parental Device Blocker,O=Local Release Build" \
        -keyalg RSA -keysize 2048 -validity 10000
    elif [[ "$use_docker" == "1" ]]; then
      "${docker_command[@]}" run --rm --platform linux/amd64 \
        --user "$(id -u):$(id -g)" \
        --volume "$keystore_dir:/keys:rw" \
        docker.io/library/gradle:8.10.2-jdk17 \
        sh -c 'set -a; . /keys/android-release.signing; set +a; keytool -genkeypair -noprompt -keystore /keys/android-release.keystore -storepass "$PDB_STORE_PASSWORD" -alias parental-device-blocker -keypass "$PDB_KEY_PASSWORD" -dname "CN=Parental Device Blocker,O=Local Release Build" -keyalg RSA -keysize 2048 -validity 10000'
    else
      echo "keytool is required to create the release signing key." >&2
      exit 1
    fi
    chmod 600 "$keystore_path"
    echo "Created protected Android release signing key at $keystore_path"
  fi
  if [[ ! -f "$signing_path" ]]; then
    echo "Release keystore exists but $signing_path is missing." >&2
    exit 1
  fi
fi

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1"
  else
    shasum -a 256 "$1"
  fi
}

signing_key_id="$(sha256_file "$keystore_path" | awk '{print substr($1, 1, 16)}')"
mkdir -p "$repo_root/artifacts/android"
output_path="$repo_root/artifacts/android/$artifact_name"

if [[ "$use_docker" == "1" ]]; then
  build_args=(
    build --platform linux/amd64
    --target "$docker_target"
    --build-arg "SIGNING_KEY_ID=$signing_key_id"
    --secret "id=parental_device_blocker_keystore,src=$keystore_path"
  )
  if [[ "$mode" == "release" ]]; then
    build_args+=(--secret "id=parental_device_blocker_signing,src=$signing_path")
  fi
  "${docker_command[@]}" "${build_args[@]}" -t "$image_name" "$repo_root/android"
  container_id="$("${docker_command[@]}" create "$image_name")"
  "${docker_command[@]}" cp "$container_id:$apk_inside" "$output_path"
  "${docker_command[@]}" rm "$container_id" >/dev/null
else
  sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "$sdk_dir" && -f "$repo_root/android/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk.dir=//p' "$repo_root/android/local.properties" | head -1)"
  fi
  if [[ -z "$sdk_dir" ]]; then
    echo "Docker is unavailable and ANDROID_HOME is not set." >&2
    exit 1
  fi
  export ANDROID_HOME="$sdk_dir"
  gradle_bin="${GRADLE_BIN:-gradle}"
  (
    cd "$repo_root/android"
    if [[ "$mode" == "debug" ]]; then
      "$gradle_bin" --no-daemon assembleDebug \
        -Pandroid.injected.signing.store.file="$keystore_path" \
        -Pandroid.injected.signing.store.password=android \
        -Pandroid.injected.signing.key.alias=androiddebugkey \
        -Pandroid.injected.signing.key.password=android
      cp app/build/outputs/apk/debug/app-debug.apk "$output_path"
    else
      # shellcheck disable=SC1090
      set -a
      # shellcheck disable=SC1090
      source "$signing_path"
      set +a
      export PDB_STORE_FILE="$keystore_path"
      "$gradle_bin" --no-daemon assembleRelease
      cp app/build/outputs/apk/release/app-release.apk "$output_path"
    fi
  )
fi

aapt="${ANDROID_HOME:-}/build-tools/34.0.0/aapt"
if [[ ! -x "$aapt" ]]; then
  aapt="$(find "${ANDROID_HOME:-/opt}" /opt /usr -path '*/build-tools/*/aapt' -type f 2>/dev/null | head -1 || true)"
fi
if [[ -n "$aapt" && -x "$aapt" ]]; then
  badging="$("$aapt" dump badging "$output_path")"
  if grep -q application-debuggable <<<"$badging"; then
    if [[ "$mode" == "release" ]]; then
      echo "Refusing to keep a debuggable release APK." >&2
      exit 1
    fi
  elif [[ "$mode" == "debug" ]]; then
    echo "Debug APK is missing application-debuggable." >&2
    exit 1
  fi
  if [[ "$mode" == "release" ]] && ! grep -q "package: name='lol.rowe.blocker' " <<<"$badging"; then
    echo "Release APK package id was not lol.rowe.blocker." >&2
    exit 1
  fi
fi

checksum_file="$output_path.sha256"
(
  cd "$repo_root/artifacts/android"
  sha256_file "$(basename "$output_path")" > "$(basename "$checksum_file")"
)
echo "$output_path"
cat "$checksum_file"
