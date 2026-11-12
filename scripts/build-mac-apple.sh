#!/bin/sh
set -eu
kinuvo_project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
source="$kinuvo_project_root/plugins/apple-frameworks/native"
library="$kinuvo_project_root/build/libKinuvoApple.dylib"
mkdir -p "$kinuvo_project_root/build"
swiftc -O -emit-library -module-name KinuvoApple -target "$(uname -m)-apple-macos14.0" \
    -module-cache-path "$kinuvo_project_root/build/swift-module-cache" \
    "$source/AppleEventKit.swift" "$source/macos/Bridge.swift" -o "$library"
codesign --force --sign - "$library" >&2
printf '%s\n' "$library"
