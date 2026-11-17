#!/bin/sh
# Rebuilds libs/tailcat/prebuilt and STAMP. Manual: needs Go (mise exec go@1.27.3 -- libs/tailcat/build.sh), Xcode and the Android NDK.
# Run it only when libs/tailcat/go changes; CI checks STAMP and never builds.
# Usage: build.sh [macos] [android] [ios]   (default: all)
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
out="$root/prebuilt"
ndk_version=${KINUVO_NDK_VERSION:-28.2.13676358}
ios_min=17.0
go_cmd=go
targets=${*:-macos android ios}

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ndk="${ANDROID_NDK_HOME:-$sdk/ndk/$ndk_version}"
go_version=$($go_cmd env GOVERSION)

build_go() { (cd "$root/go" && "$go_cmd" build -p 2 -trimpath "$@"); }

for target in $targets; do
    case "$target" in
        macos)
            rm -rf "$out/macos-arm64"; mkdir -p "$out/macos-arm64"
            CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 build_go -buildmode=c-shared -ldflags='-s -w' -o "$out/macos-arm64/libKinuvoTailcat.dylib" .
            rm -f "$out/macos-arm64/libKinuvoTailcat.h"
            codesign --force --sign - "$out/macos-arm64/libKinuvoTailcat.dylib"
            ;;
        android)
            case "$(uname -s)" in
                Darwin) host=darwin-x86_64 ;;
                Linux) host=linux-x86_64 ;;
                *) echo 'Unsupported NDK host' >&2; exit 1 ;;
            esac
            toolchain="$ndk/toolchains/llvm/prebuilt/$host/bin"
            for abi in arm64-v8a x86_64; do
                case "$abi" in
                    arm64-v8a) arch=arm64; triple=aarch64-linux-android ;;
                    x86_64) arch=amd64; triple=x86_64-linux-android ;;
                esac
                rm -rf "$out/android-$abi"; mkdir -p "$out/android-$abi"
                CGO_ENABLED=1 GOOS=android GOARCH=$arch CC="$toolchain/${triple}26-clang" \
                    build_go -buildmode=c-shared -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' -o "$out/android-$abi/libKinuvoTailcat.so" .
                rm -f "$out/android-$abi/libKinuvoTailcat.h"
            done
            ;;
        ios)
            work=$(mktemp -d)
            trap 'rm -rf "$work"' EXIT
            for slice in iphoneos iphonesimulator; do
                sysroot=$(xcrun --sdk "$slice" --show-sdk-path)
                if [ "$slice" = iphoneos ]; then flag="-miphoneos-version-min=$ios_min"; else flag="-mios-simulator-version-min=$ios_min"; fi
                cflags="-isysroot $sysroot $flag -arch arm64"
                mkdir -p "$work/$slice"
                CGO_ENABLED=1 GOOS=ios GOARCH=arm64 CC="$(xcrun --sdk "$slice" --find clang)" \
                    CGO_CFLAGS="$cflags" CGO_LDFLAGS="$cflags" \
                    build_go -buildmode=c-archive -ldflags='-s -w' -o "$work/$slice/libKinuvoTailcat.a" .
            done
            mkdir -p "$work/headers"
            mv "$work/iphoneos/libKinuvoTailcat.h" "$work/headers/KinuvoTailcat.h"
            printf 'module KinuvoTailcat {\n    header "KinuvoTailcat.h"\n    export *\n}\n' > "$work/headers/module.modulemap"
            rm -rf "$out/KinuvoTailcat.xcframework"
            xcodebuild -create-xcframework \
                -library "$work/iphoneos/libKinuvoTailcat.a" -headers "$work/headers" \
                -library "$work/iphonesimulator/libKinuvoTailcat.a" -headers "$work/headers" \
                -output "$out/KinuvoTailcat.xcframework"
            ;;
        *) echo "Unknown target $target" >&2; exit 1 ;;
    esac
done

"$root/stamp.sh" write "$go_version" "$ndk_version"
