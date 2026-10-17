#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
version="${1:-0.10.0}"
release_root="$repo_root/artifacts/ParentalDeviceBlocker-$version-win-x64"
cd "$repo_root"

run_dotnet() {
  if command -v dotnet >/dev/null 2>&1; then
    dotnet "$@"
    return
  fi

  docker run --rm \
    -v "$repo_root:/src" \
    -w /src \
    mcr.microsoft.com/dotnet/sdk:8.0 \
    dotnet "$@"
}

# The Windows runner has no `zip`, but it does ship 7-Zip and bsdtar. Each
# archiver spells exclusions differently, so use its own syntax rather than a
# file list: a POSIX temp path from Git Bash is not a path a native archiver can
# open, which is what broke the Windows job.
zip_tree() {
  local archive="$1"
  local directory="$2"
  rm -f "$archive"
  if command -v zip >/dev/null 2>&1; then
    zip -qr "$archive" "$directory" -x '*/__pycache__/*' -x '*/._*'
  elif command -v 7z >/dev/null 2>&1; then
    7z a -tzip -bso0 -bsp0 "$archive" "$directory" -xr!__pycache__ -xr!._* >/dev/null
  else
    tar -a -c -f "$archive" --exclude __pycache__ --exclude '._*' "$directory"
  fi
}

rm -rf "$release_root"
mkdir -p "$release_root/payload/service" "$release_root/payload/activity"

run_dotnet publish src/RowePcBlocker/RowePcBlocker.csproj \
  -c Release -r win-x64 --self-contained true \
  -p:Version="$version" -p:PublishSingleFile=true -p:DebugType=None -p:DebugSymbols=false \
  -o "artifacts/ParentalDeviceBlocker-$version-win-x64/payload/service"

run_dotnet publish src/RowePcActivity/RowePcActivity.csproj \
  -c Release -r win-x64 --self-contained true \
  -p:Version="$version" -p:PublishSingleFile=true -p:DebugType=None -p:DebugSymbols=false \
  -o "artifacts/ParentalDeviceBlocker-$version-win-x64/payload/activity"

cp "$repo_root/deploy/windows/"*.ps1 "$release_root/"

(
  cd "$repo_root/artifacts"
  zip_tree "ParentalDeviceBlocker-$version-win-x64.zip" "ParentalDeviceBlocker-$version-win-x64"
)

(
  cd "$repo_root"
  zip_tree "artifacts/parental_device_blocker-hacs-$version.zip" custom_components/rowe_pc_blocker
)

echo "$release_root.zip"
