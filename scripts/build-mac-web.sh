#!/bin/sh
set -eu
kinuvo_project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$kinuvo_project_root/build"
library="$kinuvo_project_root/build/libKinuvoWeb.dylib"
swiftc -O -emit-library -module-name KinuvoWeb -module-cache-path "$kinuvo_project_root/build/swift-module-cache" \
    "$kinuvo_project_root/client/ui/native/macos/OffscreenWeb.swift" -o "$library"
codesign --force --sign - "$library" >&2
