#!/bin/sh
set -eu
kinuvo_project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
sdk="$kinuvo_project_root/build/local-voice-sdk"
bundle="$kinuvo_project_root/build/voice-worker"
case "$(uname -s):$(uname -m)" in
    Darwin:*) worker=MacOS/KinuvoVoiceInference ;;
    Linux:x86_64) worker=linux-x64/KinuvoVoiceInference ;;
    MINGW*:x86_64|MSYS*:x86_64|CYGWIN*:x86_64) worker=windows-x64/KinuvoVoiceInference.exe ;;
    *) echo 'Downloaded voice is unavailable on this platform (Linux arm64 is unsupported).' >&2; exit 0 ;;
esac
mkdir -p "$bundle/$(dirname "$worker")"
mkdir -p "$sdk" "$bundle/Resources/VoiceLicenses"
cp "$kinuvo_project_root/apps/desktop/voice/licenses/"*.txt "$bundle/Resources/VoiceLicenses/"
if command -v sha256sum >/dev/null 2>&1; then hash="sha256sum"; else hash="shasum -a 256"; fi
verify() { printf '%s  %s\n' "$2" "$1" | $hash -c - >/dev/null; }
fetch() {
    if [ ! -f "$1" ] || ! verify "$1" "$3"; then
        curl --fail --location --proto '=https' --proto-redir '=https' --retry 2 "$2" -o "$1.part"
        verify "$1.part" "$3"
        mv "$1.part" "$1"
    fi
}
fetch "$sdk/c-api.h" 'https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/c-api/c-api.h' 2a1b95084be8fd1deb3228fcad2fd3f7f0258b64582f7402281ec174c7b7f4ce
case "$worker" in
    MacOS/*)
        clang -O2 -arch arm64 -arch x86_64 -I "$sdk" "$kinuvo_project_root/apps/desktop/voice/inference.c" -o "$bundle/$worker"
        codesign --force --options runtime --entitlements "$kinuvo_project_root/apps/desktop/voice/entitlements.plist" --sign - "$bundle/$worker" >&2 ;;
    *.exe)
        clang -O2 -fms-runtime-lib=static -fuse-ld=lld -I "$sdk" "$kinuvo_project_root/apps/desktop/voice/inference.c" -o "$bundle/$worker" ;;
    *) cc -O2 -I "$sdk" "$kinuvo_project_root/apps/desktop/voice/inference.c" -o "$bundle/$worker" -ldl -pthread ;;
esac
