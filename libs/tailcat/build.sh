#!/bin/sh
# Rebuilds libs/tailcat/prebuilt and STAMP. Needs Go 1.27.3, Xcode (macOS/iOS), Android NDK and Zig 0.15.2 (Linux/Windows).
# Full build on Mac: mise exec go@1.27.3 zig@0.15.2 -- libs/tailcat/build.sh
# Usage: build.sh [macos] [linux] [windows] [android] [ios]   (default: all)
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
out="$root/prebuilt"
ndk_version=${KINUVO_NDK_VERSION:-28.2.13676358}
ios_min=17.0
go_cmd=go
targets=${*:-macos linux windows android ios}

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ndk="${ANDROID_NDK_HOME:-$sdk/ndk/$ndk_version}"
go_version=$($go_cmd env GOVERSION)

build_go() { (cd "$root/go" && "$go_cmd" build -p 2 -trimpath "$@"); }

build_shared() {
    mkdir -p "$out/$(dirname "$1")"
    CGO_ENABLED=1 build_go -buildmode=c-shared -ldflags='-s -w' -o "$out/$1" .
    rm -f "$out/${1%.*}.h"
}

case " $targets " in
    *" linux "* | *" windows "*)
        [ "$(zig version)" = 0.15.2 ] || { echo "Linux and Windows builds require Zig 0.15.2" >&2; exit 1; }
        ;;
esac

for target in $targets; do
    case "$target" in
        macos)
            sysroot=$(xcrun --sdk macosx --show-sdk-path)
            for arch in arm64 amd64; do
                case "$arch" in
                    arm64) dir=macos-arm64 ;;
                    amd64) dir=macos-x64 ;;
                esac
                GOOS=darwin GOARCH=$arch SDKROOT="$sysroot" MACOSX_DEPLOYMENT_TARGET=13.0 \
                    CC="$(xcrun --sdk macosx --find clang)" build_shared "$dir/libKinuvoTailcat.dylib"
                codesign --force --sign - "$out/$dir/libKinuvoTailcat.dylib"
            done
            ;;
        linux)
            GOOS=linux GOARCH=amd64 CC='zig cc -target x86_64-linux-gnu.2.28' build_shared linux-x64/libKinuvoTailcat.so
            GOOS=linux GOARCH=arm64 CC='zig cc -target aarch64-linux-gnu.2.28' build_shared linux-arm64/libKinuvoTailcat.so
            ;;
        windows)
            GOOS=windows GOARCH=amd64 CC='zig cc -target x86_64-windows-gnu' build_shared windows-x64/KinuvoTailcat.dll
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
