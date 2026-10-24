#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$root"
kinuvo_project_root=$root
. "$kinuvo_project_root/scripts/project-env.sh"

./kotlin build -m web -v release
./kotlin package -m cli -f executable-jar

web="$root/build/tasks/_web_buildWasmJsAppWasmJsRelease"
jar="$root/build/tasks/_cli_executableJarJvm/cli-jvm-executable.jar"
staging=$(mktemp -d)
trap 'rm -rf "$staging"' EXIT HUP INT TERM
cp -R "$web" "$staging/web"
printf '%s' "${KINUVO_VERSION:-dev}" > "$staging/kinuvo-version"
jar uf "$jar" -C "$staging" web -C "$staging" kinuvo-version

printf '\nServer with web app: %s\n' "$jar"
