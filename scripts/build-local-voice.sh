#!/bin/sh
set -eu
kinuvo_project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
. "$kinuvo_project_root/scripts/project-env.sh"
sdk="$kinuvo_project_root/build/local-voice-sdk"
bundle="$kinuvo_project_root/build/voice-worker"
mkdir -p "$sdk" "$bundle/MacOS" "$bundle/Frameworks" "$bundle/Resources/VoiceLicenses"
cp "$kinuvo_project_root/apps/desktop/voice/licenses/"*.txt "$bundle/Resources/VoiceLicenses/"
verify() { printf '%s  %s\n' "$2" "$1" | shasum -a 256 -c - >/dev/null; }
fetch() {
    if [ ! -f "$1" ] || ! verify "$1" "$3"; then
        curl --fail --location --proto '=https' --proto-redir '=https' --retry 2 "$2" -o "$1.part"
        verify "$1.part" "$3"
        mv "$1.part" "$1"
    fi
}
fetch "$sdk/runtime.tar.bz2" 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-osx-universal2-shared-lib.tar.bz2' c1cca5b4a1867543e27d42fbea513983e73f629d0aa53b43b51555a71d2108d9
fetch "$sdk/c-api.h" 'https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/c-api/c-api.h' 2a1b95084be8fd1deb3228fcad2fd3f7f0258b64582f7402281ec174c7b7f4ce
tar -xjf "$sdk/runtime.tar.bz2" -C "$sdk" --strip-components=1
cp "$sdk/lib/libsherpa-onnx-c-api.dylib" "$sdk/lib/libonnxruntime.dylib" "$bundle/Frameworks/"
clang -O2 -I "$sdk" "$kinuvo_project_root/apps/desktop/voice/inference.c" -L "$sdk/lib" -lsherpa-onnx-c-api -Wl,-rpath,@executable_path/../Frameworks -o "$bundle/MacOS/KinuvoVoiceInference"
for lib in "$bundle"/Frameworks/*.dylib; do codesign --force --sign - "$lib" >&2; done
codesign --force --sign - "$bundle/MacOS/KinuvoVoiceInference" >&2
