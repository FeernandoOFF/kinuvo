#!/bin/sh
set -eu
kinuvo_project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
source="$kinuvo_project_root/apps/cli/native/macos"
launcher="$kinuvo_project_root/build/kinuvo-cli-launcher"
mkdir -p "$kinuvo_project_root/build"
clang -O2 -Wall -Wextra -Werror -mmacosx-version-min=14.0 -Wl,-export_dynamic \
    -Wl,-sectcreate,__TEXT,__info_plist,"$source/Info.plist" \
    "$source/launcher.c" -o "$launcher"
codesign --force --sign - --identifier dev.kinuvo.cli "$launcher" >&2
printf '%s\n' "$launcher"
