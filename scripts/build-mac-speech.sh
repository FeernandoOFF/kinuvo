#!/bin/sh
set -eu
kinuvo_project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
. "$kinuvo_project_root/scripts/project-env.sh"
"$kinuvo_project_root/scripts/build-local-voice.sh"
source="$kinuvo_project_root/shared/ui/native/macos"
library="$kinuvo_project_root/build/libKinuvoSpeech.dylib"
swiftc -O -emit-library -module-name KinuvoSpeech -module-cache-path "$kinuvo_project_root/build/swift-module-cache" \
    "$source/Bridge.swift" "$source/Speech.swift" "$source/LocalSpeech.swift" -o "$library"
codesign --force --sign - "$library" >&2
# Remove the rejected capture helper from earlier development builds.
rm -f "$kinuvo_project_root/build/KinuvoSpeech.app/Contents/MacOS/KinuvoSpeech" "$kinuvo_project_root/build/KinuvoSpeech.app/Contents/Info.plist"
printf '%s\n' "$library"
