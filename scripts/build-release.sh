#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
version="${1:-0.9.2}"
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
  rm -f "ParentalDeviceBlocker-$version-win-x64.zip"
  zip -qr "ParentalDeviceBlocker-$version-win-x64.zip" "ParentalDeviceBlocker-$version-win-x64"
)

(
  cd "$repo_root"
  rm -f "artifacts/parental_device_blocker-hacs-$version.zip"
  zip -qr "artifacts/parental_device_blocker-hacs-$version.zip" custom_components/rowe_pc_blocker \
    -x '*/__pycache__/*' -x '*/._*'
)

echo "$release_root.zip"
