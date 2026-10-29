#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$root"

./kotlin build -m web -v release
./kotlin package -m cli -f executable-jar

web="$root/build/tasks/_web_buildWasmJsAppWasmJsRelease"
jar="$root/build/tasks/_cli_executableJarJvm/cli-jvm-executable.jar"
staging=$(mktemp -d)
trap 'rm -rf "$staging"' EXIT HUP INT TERM
cp -R "$web" "$staging/web"
printf '%s' "${KINUVO_VERSION:-dev}" > "$staging/kinuvo-version"
jar uf "$jar" -C "$staging" web -C "$staging" kinuvo-version

if [ "$(uname -s)" = Darwin ]; then
    "$root/scripts/build-local-voice.sh"
    mkdir -p "$staging/server-voice"
    cp -R "$root/build/voice-worker/MacOS" "$staging/server-voice/"
    cp -R "$root/build/voice-worker/Frameworks" "$staging/server-voice/"
    cp -R "$root/build/voice-worker/Resources" "$staging/server-voice/"
    jar uf "$jar" -C "$staging" server-voice
fi

printf '\nServer with web app: %s\n' "$jar"
