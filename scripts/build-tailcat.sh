#!/bin/sh
set -eu
kinuvo_project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
case "$(uname -s)" in
    Darwin) target_os=darwin; suffix=dylib ;;
    Linux) target_os=linux; suffix=so ;;
    *) echo 'Tailcat supports macOS and Linux' >&2; exit 1 ;;
esac
case "${1:-$(uname -m)}" in
    arm64|aarch64) target_arch=arm64 ;;
    x86_64|amd64|x64) target_arch=amd64 ;;
    *) echo 'Expected arm64 or x64' >&2; exit 1 ;;
esac
output="$kinuvo_project_root/build/tailcat/$target_os-$target_arch"
mkdir -p "$output"
cd "$kinuvo_project_root/native/tailcat"
CGO_ENABLED=1 GOOS=$target_os GOARCH=$target_arch go build -p 2 -trimpath -buildmode=c-shared -ldflags='-s -w' -o "$output/libKinuvoTailcat.$suffix" .
if [ "$target_os" = darwin ]; then
    codesign --force --sign - "$output/libKinuvoTailcat.$suffix" >&2
fi
case "$(uname -m)" in arm64|aarch64) host_arch=arm64 ;; *) host_arch=amd64 ;; esac
if [ "$target_arch" = "$host_arch" ]; then
    cp "$output/libKinuvoTailcat.$suffix" "$kinuvo_project_root/build/"
fi
printf '%s\n' "$output/libKinuvoTailcat.$suffix"
