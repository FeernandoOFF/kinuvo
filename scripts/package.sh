#!/usr/bin/env bash
# Builds release artifacts into dist/; cli and desktop target this machine's OS and architecture.
#   scripts/package.sh jar [VERSION]         kinuvo.jar: the Server and CLI with the web app, for any Java 25+
#   scripts/package.sh cli [VERSION] [JAR]   kinuvo-<os>-<arch>.tar.gz (.zip on Windows): bin/kinuvo, lib/kinuvo.jar, runtime/
#   scripts/package.sh desktop [VERSION]     Kinuvo-desktop-<os>-<arch>.dmg | .deb | .zip
# JAVA_HOME must be a JetBrains Runtime 25 SDK (jlink, jpackage and jmods).
set -euo pipefail
cd "$(dirname "$0")/.."

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
if [ "$target" = desktop ] && [ "$os" = macos ] && [ -n "${KINUVO_MACOS_NOTARY_PROFILE:-}" ]; then
  : "${KINUVO_MACOS_SIGN_IDENTITY:?Notarization requires KINUVO_MACOS_SIGN_IDENTITY}"
  [ "${KINUVO_DESKTOP_APP_IMAGE:-0}" != 1 ] || { echo 'Notarization requires a DMG, not KINUVO_DESKTOP_APP_IMAGE=1.' >&2; exit 1; }
fi
java_major=$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$java_home/release")
[ "${java_major:-0}" -ge 25 ] || { echo "Packaging requires Java 25+: $java_home" >&2; exit 1; }
# From jdeps --print-module-deps, plus service-loaded jdk.zipfs and jdk.charsets.
modules=java.base,java.desktop,java.instrument,java.logging,java.management,jdk.management,java.naming,java.net.http,jdk.httpserver,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.localedata
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

tailcat_library() { # platform
  case "$1" in
    macos-arm64 | macos-x64) printf '%s/libKinuvoTailcat.dylib\n' "$1" ;;
    linux-arm64 | linux-x64) printf '%s/libKinuvoTailcat.so\n' "$1" ;;
    windows-x64) printf '%s/KinuvoTailcat.dll\n' "$1" ;;
  esac
}

copy_tailcat() { # platform destination
  local library
  library=$(tailcat_library "$1")
  [ -n "$library" ] || return 0
  local source=libs/tailcat/prebuilt/$library
  [ -f "$source" ] || { echo "Missing $source; run mise run libs:fetch" >&2; exit 1; }
  mkdir -p "$2"
  cp "$source" "$2/"
}

package_jar() {
  KINUVO_VERSION=$version ./package-server.sh
  cp build/tasks/_cli_executableJarJvm/cli-jvm-executable.jar "$dist/kinuvo.jar"
  restore_collisions cli "$dist/kinuvo.jar"
  # The JAR is portable, so it carries every desktop Tailcat library whatever host packages it.
  local platform natives=$work/tailcat/BOOT-INF/classes/natives
  for platform in macos-arm64 macos-x64 linux-arm64 linux-x64 windows-x64; do
    copy_tailcat "$platform" "$natives/$platform"
  done
  cp libs/tailcat/LICENSE.tailcat libs/tailcat/LICENSE.tailpocket "$natives/"
  (cd "$work/tailcat" && "$java_home/bin/jar" uf "$dist/kinuvo.jar" BOOT-INF/classes)
  if [ -n "${KINUVO_VOICE_WORKERS:-}" ]; then
    (cd "$KINUVO_VOICE_WORKERS" && "$java_home/bin/jar" uf "$dist/kinuvo.jar" server-voice)
  else
    bundle_voice "$dist/kinuvo.jar"
  fi
  allow_native_access "$dist/kinuvo.jar"
}

bundle_voice() {
  if [ "$os" = macos ] || [ "$arch" = x64 ]; then
    ./scripts/build-local-voice.sh
    mkdir -p "$work/voice/server-voice"
    cp -R build/voice-worker/. "$work/voice/server-voice/"
    (cd "$work/voice" && "$java_home/bin/jar" uf "$1" server-voice)
  fi
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
  bundle_voice "$app/lib/kinuvo.jar"
  python3 scripts/package-natives.py "$app/lib/kinuvo.jar" "$os" "$arch"
  if [ -n "$(tailcat_library "$os-$arch")" ]; then
    copy_tailcat "$os-$arch" "$app/lib"
    cp libs/tailcat/LICENSE.tailcat libs/tailcat/LICENSE.tailpocket "$app/lib/"
  fi
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
  ./kotlin package -m desktop -p jvm -f executable-jar
  mkdir -p "$work/input"
  cp build/tasks/_desktop_executableJarJvm/desktop-jvm-executable.jar "$work/input/kinuvo-desktop.jar"
  restore_collisions desktop "$work/input/kinuvo-desktop.jar"
  allow_native_access "$work/input/kinuvo-desktop.jar"
  python3 scripts/package-natives.py "$work/input/kinuvo-desktop.jar" "$os" "$arch"
  modules=$modules,jdk.accessibility runtime "$work/runtime"
  local args=(--name Kinuvo --input "$work/input" --main-jar kinuvo-desktop.jar --runtime-image "$work/runtime" --dest "$work/out"
    --java-options --enable-native-access=ALL-UNNAMED
    --java-options '-Dcompose.application.resources.dir=$APPDIR')
  # jpackage wants a numeric version, and macOS rejects a leading 0.
  local numeric=${version#v}; numeric=${numeric%%-*}
  if [[ $numeric =~ ^[0-9]+(\.[0-9]+){0,2}$ ]] && { [ "$os" != macos ] || [ "${numeric%%.*}" != 0 ]; }; then
    args+=(--app-version "$numeric")
  fi
  if [ -n "$(tailcat_library "$os-$arch")" ]; then
    copy_tailcat "$os-$arch" "$work/input"
    cp libs/tailcat/LICENSE.tailcat libs/tailcat/LICENSE.tailpocket "$work/input/"
  fi
  if [ "$os" = macos ] || [ "$arch" = x64 ]; then
    ./scripts/build-local-voice.sh
    cp -R build/voice-worker "$work/input/"
  fi
  case $os in
    macos)
      args+=(--java-options -XstartOnFirstThread)
      ./scripts/build-mac-speech.sh >/dev/null
      ./scripts/build-mac-apple.sh >/dev/null
      cp build/libKinuvoSpeech.dylib "$work/input/"
      ./scripts/build-mac-service.sh >/dev/null
      cp build/libKinuvoService.dylib "$work/input/"
      cp build/libKinuvoApple.dylib "$work/input/"
      local identity=dev.kinuvo.desktop
      args+=(--mac-package-identifier "$identity")
      "$java_home/bin/jpackage" --type app-image --icon apps/desktop/icons/Kinuvo.icns "${args[@]}"
      local bundle="$work/out/Kinuvo.app"
      # The login service: `kinuvo service install` registers this agent, which runs `Kinuvo --serve` under the app's identity.
      mkdir -p "$bundle/Contents/Library/LaunchAgents"
      cp apps/desktop/service/dev.kinuvo.server.plist "$bundle/Contents/Library/LaunchAgents/"
      /usr/libexec/PlistBuddy -c 'Add :NSSpeechRecognitionUsageDescription string Kinuvo turns speech into messages for your Agent. Apple may process audio when on-device recognition is unavailable.' "$bundle/Contents/Info.plist"
      /usr/libexec/PlistBuddy -c 'Add :NSRemindersFullAccessUsageDescription string Enabled Agents can read and change your reminders for you.' "$bundle/Contents/Info.plist"
      /usr/libexec/PlistBuddy -c 'Add :NSCalendarsFullAccessUsageDescription string Enabled Agents can read and change your calendar events for you.' "$bundle/Contents/Info.plist"
      /usr/libexec/PlistBuddy -c 'Set :NSMicrophoneUsageDescription Kinuvo listens while Voice chat or Dictation is active.' "$bundle/Contents/Info.plist"
      if [ -n "${KINUVO_MACOS_SIGN_IDENTITY:-}" ]; then
        python3 scripts/sign-macos.py "$bundle" "$KINUVO_MACOS_SIGN_IDENTITY"
      else
        codesign --force --options runtime --sign - --entitlements apps/desktop/voice/entitlements.plist "$bundle/Contents/app/voice-worker/MacOS/KinuvoVoiceInference"
        codesign --force --sign - --entitlements apps/desktop/entitlements.plist "$bundle"
      fi
      if [ "${KINUVO_DESKTOP_APP_IMAGE:-0}" = 1 ]; then
        # Replace only the generated app image; do not merge stale runtime files into it.
        rm -rf "$dist/Kinuvo.app"
        cp -R "$bundle" "$dist/"
      else
        local dmg="$work/Kinuvo-desktop-$os-$arch.dmg"
        if [ -n "${KINUVO_MACOS_SIGN_IDENTITY:-}" ]; then
          ln -s /Applications "$work/out/Applications"
          hdiutil create -volname Kinuvo -srcfolder "$work/out" -format UDZO "$dmg"
          local signing=(--timestamp --sign "$KINUVO_MACOS_SIGN_IDENTITY")
          if [ -n "${KINUVO_MACOS_KEYCHAIN:-}" ]; then signing+=(--keychain "$KINUVO_MACOS_KEYCHAIN"); fi
          codesign "${signing[@]}" "$dmg"
          codesign --verify --strict "$dmg"
          if [ -n "${KINUVO_MACOS_NOTARY_PROFILE:-}" ]; then
            ./scripts/notarize-macos.sh "$dmg" "$dist"
            spctl --assess --type execute --verbose=2 "$bundle"
          fi
        else
          "$java_home/bin/jpackage" --type dmg --app-image "$bundle" --dest "$work/dmg"
          cp "$work"/dmg/*.dmg "$dmg"
        fi
        cp "$dmg" "$dist/"
      fi ;;
    linux)
      "$java_home/bin/jpackage" --type deb --linux-shortcut --linux-package-name kinuvo-desktop --icon apps/desktop/icons/Kinuvo.png "${args[@]}"
      cp "$work"/out/*.deb "$dist/Kinuvo-desktop-$os-$arch.deb" ;;
    windows)
      powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-windows-speech.ps1 -OutputPath "$(cygpath -w "$work/input/KinuvoSpeech.exe")"
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
