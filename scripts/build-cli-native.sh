#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
: "${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM JDK 25 installation}"
jar=${1:-$root/build/tasks/_cli_executableJarJvm/cli-jvm-executable.jar}
[[ -f "$jar" ]] || { echo "Build first: mise run kotlin -- package -m cli -f executable-jar" >&2; exit 1; }
work="$root/build/native-image"
mkdir -p "$work/tmp"
export TMPDIR="$work/tmp"
rm -rf "$work/flat"
mkdir -p "$work/flat"
unzip -q "$jar" -d "$work/flat"
"$GRAALVM_HOME/bin/javac" -cp "$work/probe:$work/flat/BOOT-INF/classes:$work/flat/BOOT-INF/lib/*" \
  -d "$work/probe" "$root/apps/cli/native-image/NativeCli.java" "$root/apps/cli/native-image/SerializationFeature.java"
# Include root resources added by package-server.sh as well as BOOT-INF/classes.
exec "$GRAALVM_HOME/bin/native-image" -J-Xmx8g -J-Djava.io.tmpdir="$work/tmp" --parallelism=4 --no-fallback \
  --enable-native-access=ALL-UNNAMED -H:+ForeignAPISupport \
  --features=SerializationFeature --enable-url-protocols=http,https \
  -H:ConfigurationFileDirectories="$root/apps/cli/native-image/metadata" \
  "-H:NativeLinkerOption=-Wl,-sectcreate,__TEXT,__info_plist,$root/apps/cli/native-image/Info.plist" \
  -cp "$work/probe:$work/flat/BOOT-INF/classes:$work/flat/BOOT-INF/lib/*:$work/flat" \
  NativeCli "$work/kinuvo"
