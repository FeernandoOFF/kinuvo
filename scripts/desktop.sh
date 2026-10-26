#!/bin/sh
set -eu
kinuvo_project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
. "$kinuvo_project_root/scripts/project-env.sh"
cd "$kinuvo_project_root"
if [ "$(uname -s)" != Darwin ]; then
    case "$(uname -s)" in
        MINGW* | MSYS* | CYGWIN*) powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-windows-speech.ps1 ;;
    esac
    exec ./kotlin run -m desktop "$@"
fi
for argument in "$@"; do
    if [ "$argument" = --compose-hot-reload ]; then
        ./scripts/build-mac-speech.sh >/dev/null
        exec ./kotlin run -m desktop "$@"
    fi
done
# TCC attributes helper requests to the responsible app. A bare JVM launched by
# a terminal cannot reliably request both speech and microphone access.
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
exec open -n -W --env "KINUVO_HOME=$KINUVO_HOME" --env "KINUVO_EMBEDDED_PORT=$KINUVO_EMBEDDED_PORT" \
    --stdout "$KINUVO_HOME/logs/desktop-output.log" --stderr "$KINUVO_HOME/logs/desktop-error.log" "$kinuvo_project_root/dist/Kinuvo.app" --args "$@"
