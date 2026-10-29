#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export KINUVO_HOME="${KINUVO_HOME:-$HOME/.kinuvo}"
export KINUVO_EMBEDDED_PORT="${KINUVO_EMBEDDED_PORT:-8787}"
if [ "$(uname -s)" != Darwin ]; then
    case "$(uname -s)" in
        MINGW* | MSYS* | CYGWIN*) powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-windows-speech.ps1 ;;
    esac
    exec ./kotlin run -m desktop "$@"
fi
for argument in "$@"; do
    if [ "$argument" = --compose-hot-reload ]; then
        ./scripts/build-mac-speech.sh >/dev/null
        exec ./kotlin run -m desktop --jvm-args=--enable-native-access=ALL-UNNAMED "$@"
    fi
done
# Launch the host app through LaunchServices so it owns in-process TCC requests.
speech_java=${JAVA_HOME:-}
case "$speech_java" in *jbr*) ;; *) speech_java= ;; esac
find_speech_java() {
    for release in "$HOME/Library/Caches/JetBrains/Kotlin/extract.cache/"*/jbr*/Contents/Home/release; do
        [ -f "$release" ] || continue
        case "$(head -n 1 "$release")" in
            'JAVA_VERSION="25.'*) printf '%s\n' "${release%/release}"; return ;;
        esac
    done
}
if [ -z "$speech_java" ]; then speech_java=$(find_speech_java); fi
if [ -z "$speech_java" ]; then
    ./kotlin build -m desktop -p jvm
    speech_java=$(find_speech_java)
fi
[ -n "$speech_java" ] || { echo 'Set JAVA_HOME to a JetBrains Runtime 25 SDK to package macOS Voice chat.' >&2; exit 1; }
JAVA_HOME="$speech_java" KINUVO_DESKTOP_APP_IMAGE=1 ./scripts/package.sh desktop
umask 077
mkdir -p "$KINUVO_HOME/logs"
exec python3 scripts/launch-desktop.py "$PWD/dist/Kinuvo.app" "$@"
