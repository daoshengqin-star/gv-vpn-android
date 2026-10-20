#!/bin/sh
set -eu

# Private recipe draft. This script neither signs, installs nor uploads an APK.
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${ANDROID_NDK_HOME:?Point to the verified Android NDK r29 installation}"
test "$(go env GOVERSION)" = go1.27.2
cd "$project_dir/engine-reference/gv-native"
go mod download
go mod verify
python3 "$project_dir/fdroid/prepare-native-tools.py"
export PATH="$project_dir/fdroid/.build-tools/bin:$PATH"
export TMPDIR="$project_dir/.gomobile-tmp"
mkdir -p "$TMPDIR"
mkdir -p "$project_dir/app/libs"
gomobile bind -v -target android/arm64,android/amd64,android/arm \
  -androidapi 28 -trimpath \
  -ldflags='-s -w -buildid= -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384' \
  -o "$project_dir/app/libs/libv2ray.aar" ./
