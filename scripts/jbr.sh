#!/usr/bin/env bash
# Downloads the JetBrains Runtime SDK for this runner and exports JAVA_HOME (setup-java hits GitHub API rate limits on macOS).
set -euo pipefail
version=25.0.4.1-b635.72
case "$(uname -s)" in Darwin) os=osx ;; Linux) os=linux ;; *) os=windows ;; esac
case "$(uname -m)" in arm64 | aarch64) arch=aarch64 ;; *) arch=x64 ;; esac
dir=${RUNNER_TEMP:-/tmp}/jbr
mkdir -p "$dir"
curl -fsSL "https://cache-redirector.jetbrains.com/intellij-jbr/jbrsdk-${version%-*}-$os-$arch-${version#*-}.tar.gz" | (cd "$dir" && tar -xz --strip-components 1)
home=$dir
[ -d "$dir/Contents/Home" ] && home=$dir/Contents/Home
"$home/bin/java" -version
echo "JAVA_HOME=$home" >> "$GITHUB_ENV"
