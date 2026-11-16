#!/bin/sh
set -eu
kinuvo_project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
if [ "${1:-}" = android ]; then
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
    ndk="${ANDROID_NDK_HOME:-$sdk/ndk/28.2.13676358}"
    case "$(uname -s)" in
        Darwin) host=darwin-x86_64 ;;
        Linux) host=linux-x86_64 ;;
        *) echo 'Unsupported NDK host' >&2; exit 1 ;;
    esac
    toolchain="$ndk/toolchains/llvm/prebuilt/$host/bin"
    cd "$kinuvo_project_root/native/tailcat"
    for abi in arm64-v8a x86_64; do
        case "$abi" in
            arm64-v8a) arch=arm64; triple=aarch64-linux-android ;;
            x86_64) arch=amd64; triple=x86_64-linux-android ;;
        esac
        output="$kinuvo_project_root/build/tailcat/android-$arch"
        mkdir -p "$output"
        CGO_ENABLED=1 GOOS=android GOARCH=$arch CC="$toolchain/${triple}26-clang" \
            go build -p 2 -trimpath -buildmode=c-shared -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' \
            -o "$output/libKinuvoTailcat.so" .
        rm -f "$output/libKinuvoTailcat.h"
        for app in android android-play; do
            packaged="$kinuvo_project_root/apps/$app/jniLibs/$abi"
            mkdir -p "$packaged"
            cp "$output/libKinuvoTailcat.so" "$packaged/"
        done
        printf '%s\n' "$output/libKinuvoTailcat.so"
    done
    exit 0
fi
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
