#!/bin/sh
set -eu
kinuvo_project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$kinuvo_project_root/build"
library="$kinuvo_project_root/build/libKinuvoService.dylib"
swiftc -O -emit-library -module-name KinuvoService -module-cache-path "$kinuvo_project_root/build/swift-module-cache" \
    "$kinuvo_project_root/apps/desktop/service/ServiceAgent.swift" -o "$library"
codesign --force --sign - "$library" >&2
printf '%s\n' "$library"
