#!/usr/bin/env bash
# Builds release artifacts into dist/; cli and desktop target this machine's OS and architecture.
#   scripts/package.sh jar [VERSION]         kinuvo.jar: the Server and CLI with the web app, for any Java 25+
#   scripts/package.sh cli [VERSION] [JAR]   kinuvo-<os>-<arch>.tar.gz (.zip on Windows): bin/kinuvo, lib/kinuvo.jar, runtime/
#   scripts/package.sh desktop [VERSION]     Kinuvo-desktop-<os>-<arch>.dmg | .deb | .zip
# JAVA_HOME must be a JetBrains Runtime 25 SDK (jlink, jpackage and jmods).
set -euo pipefail
cd "$(dirname "$0")/.."
kinuvo_project_root=$PWD
. "$kinuvo_project_root/scripts/project-env.sh"

target=${1:?cli or desktop}
version=${2:-dev}
case "$(uname -s)" in
  Darwin) os=macos ;;
  Linux) os=linux ;;
  MINGW* | MSYS* | CYGWIN*) os=windows ;;
  *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=x64 ;;
  arm64 | aarch64) arch=arm64 ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

java_home=${JAVA_HOME:?Set JAVA_HOME to a JetBrains Runtime 25 SDK}
# From jdeps --print-module-deps, plus service-loaded jdk.zipfs and jdk.charsets.
modules=java.base,java.desktop,java.instrument,java.logging,java.management,java.net.http,jdk.httpserver,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.localedata
dist=$PWD/dist
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$dist"

# Uncompressed modules make a smaller download (the archive compresses better) and start faster.
runtime() { # runtime <dir> [jlink args]
  "$java_home/bin/jlink" --add-modules "$modules" --strip-debug --no-header-files --no-man-pages \
    --compress zip-0 --output "$@"
}

archive() { # archive <dir-to-pack> <name-without-extension>
  if [ "$os" = windows ]; then
    (cd "$(dirname "$1")" && powershell -NoProfile -Command "Compress-Archive -Path '$(basename "$1")' -DestinationPath '$(cygpath -w "$dist/$2.zip")' -Force")
  else
    tar -C "$(dirname "$1")" -czf "$dist/$2.tar.gz" "$(basename "$1")"
  fi
}

# The toolchain names nested jars <artifact>-<version>.jar, so same-named artifacts from different groups overwrite
# each other (org.jetbrains.androidx.lifecycle vs androidx.lifecycle). Add them back under group-qualified names.
restore_collisions() { # restore_collisions <module> <executable-jar>
  local m2 dir=$work/collisions
  for m2 in "$HOME/Library/Caches/JetBrains/Kotlin/.m2.cache" "${XDG_CACHE_HOME:-$HOME/.cache}/JetBrains/Kotlin/.m2.cache" \
    "${LOCALAPPDATA:-/nonexistent}/JetBrains/Kotlin/.m2.cache"; do [ -d "$m2" ] && break; done
  [ -d "$m2" ] || { echo "Kotlin Toolchain Maven cache not found" >&2; exit 1; }
  mkdir -p "$dir/BOOT-INF/lib"
  ./kotlin show dependencies -m "$1" | awk '{
      for (i = 1; i <= NF; i++) if ($i ~ /^[^:]+:[^:]+:[^:]+$/) {
        split($i, p, ":"); v = ($(i + 1) == "->") ? $(i + 2) : p[3]; print p[1], p[2], v
      }
    }' | sort -u | awk '{ g[$2 "-" $3] = g[$2 "-" $3] " " $1; n[$2 "-" $3]++; a[$2 "-" $3] = $2; v[$2 "-" $3] = $3 }
      END { for (k in n) if (n[k] > 1) { split(substr(g[k], 2), gs, " "); for (j in gs) print gs[j], a[k], v[k] } }' |
    while read -r group artifact ver; do
      local file="$m2/${group//.//}/$artifact/$ver/$artifact-$ver.jar"
      if [ -f "$file" ]; then cp "$file" "$dir/BOOT-INF/lib/$group-$artifact-$ver.jar"; else echo "Not in the Maven cache: $file" >&2; fi
    done
  [ -n "$(ls "$dir/BOOT-INF/lib")" ] || return 0
  (cd "$dir" && "$java_home/bin/jar" xf "$2" BOOT-INF/classpath.idx &&
    for f in BOOT-INF/lib/*.jar; do printf -- '- "%s"\n' "$f" >> BOOT-INF/classpath.idx; done &&
    "$java_home/bin/jar" -u -0 -f "$2" BOOT-INF)
  echo "Restored colliding jars: $(ls "$dir/BOOT-INF/lib" | tr '\n' ' ')"
}

allow_native_access() { # Skiko, QuickJS and Rive load native libraries
  printf 'Enable-Native-Access: ALL-UNNAMED\n' > "$work/native-access.mf"
  "$java_home/bin/jar" ufm "$1" "$work/native-access.mf"
}

package_jar() {
  KINUVO_VERSION=$version ./package-server.sh
  cp build/tasks/_cli_executableJarJvm/cli-jvm-executable.jar "$dist/kinuvo.jar"
  restore_collisions cli "$dist/kinuvo.jar"
  allow_native_access "$dist/kinuvo.jar"
}

package_cli() {
  local jar=${1:-}
  if [ -z "$jar" ]; then
    package_jar
    jar=$dist/kinuvo.jar
  fi
  local app=$work/kinuvo
  mkdir -p "$app/bin" "$app/lib"
  cp "$jar" "$app/lib/kinuvo.jar"
  runtime "$app/runtime" --include-locales en
  rm -rf "$app/runtime/lib/fonts"
  if [ "$os" = windows ]; then
    printf '@echo off\r\n"%%~dp0..\\runtime\\bin\\java.exe" %%KINUVO_JAVA_OPTS%% -jar "%%~dp0..\\lib\\kinuvo.jar" %%*\r\n' > "$app/bin/kinuvo.cmd"
  else
    cat > "$app/bin/kinuvo" <<'EOF'
#!/bin/sh
self=$0
while [ -L "$self" ]; do
  link=$(readlink "$self")
  case $link in /*) self=$link ;; *) self=$(dirname "$self")/$link ;; esac
done
home=$(cd "$(dirname "$self")/.." && pwd)
exec "$home/runtime/bin/java" ${KINUVO_JAVA_OPTS:-} -jar "$home/lib/kinuvo.jar" "$@"
EOF
    chmod +x "$app/bin/kinuvo"
  fi
  archive "$app" "kinuvo-$os-$arch"
}

package_desktop() {
  ./kotlin package -m desktop -f executable-jar
  mkdir -p "$work/input"
  cp build/tasks/_desktop_executableJarJvm/desktop-jvm-executable.jar "$work/input/kinuvo-desktop.jar"
  restore_collisions desktop "$work/input/kinuvo-desktop.jar"
  allow_native_access "$work/input/kinuvo-desktop.jar"
  modules=$modules,jdk.accessibility runtime "$work/runtime"
  local args=(--name Kinuvo --input "$work/input" --main-jar kinuvo-desktop.jar --runtime-image "$work/runtime" --dest "$work/out"
    --java-options --enable-native-access=ALL-UNNAMED)
  # jpackage wants a numeric version, and macOS rejects a leading 0.
  local numeric=${version#v}; numeric=${numeric%%-*}
  if [[ $numeric =~ ^[0-9]+(\.[0-9]+){0,2}$ ]] && { [ "$os" != macos ] || [ "${numeric%%.*}" != 0 ]; }; then
    args+=(--app-version "$numeric")
  fi
  case $os in
    macos)
      "$java_home/bin/jpackage" --type dmg --icon apps/desktop/icons/Kinuvo.icns "${args[@]}"
      cp "$work"/out/*.dmg "$dist/Kinuvo-desktop-$os-$arch.dmg" ;;
    linux)
      "$java_home/bin/jpackage" --type deb --linux-shortcut --linux-package-name kinuvo-desktop --icon apps/desktop/icons/Kinuvo.png "${args[@]}"
      cp "$work"/out/*.deb "$dist/Kinuvo-desktop-$os-$arch.deb" ;;
    windows)
      "$java_home/bin/jpackage" --type app-image --icon apps/desktop/icons/Kinuvo.ico "${args[@]}"
      archive "$work/out/Kinuvo" "Kinuvo-desktop-$os-$arch" ;;
  esac
}

case $target in
  jar) package_jar ;;
  cli) package_cli "${3:-}" ;;
  desktop) package_desktop ;;
  *) echo "Unknown target: $target (cli or desktop)" >&2; exit 1 ;;
esac
ls -lh "$dist"
