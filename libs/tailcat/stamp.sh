#!/bin/sh
# stamp.sh write [go-version ndk-version] | check
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
stamp="$root/STAMP"

sha() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

sources() {
    (cd "$root/go" && find . -type f \( -name '*.go' -o -name '*.c' -o -name go.mod -o -name go.sum \) | LC_ALL=C sort | while read -r file; do
        printf '%s  %s\n' "$(sha "$file" | cut -d' ' -f1)" "$file"
    done)
}

combined() { { sources; printf 'go=%s\nndk=%s\n' "$1" "$2"; } | sha | cut -d' ' -f1; }

artifacts() {
    (cd "$root/prebuilt" && find . -type f ! -name .DS_Store | LC_ALL=C sort | while read -r file; do
        printf '%s  %s\n' "$(sha "$file" | cut -d' ' -f1)" "$file"
    done)
}

field() { sed -n "s/^$1=//p" "$stamp"; }

case "${1:-}" in
    write)
        go_version=${2:?go version}
        ndk_version=${3:?ndk version}
        {
            printf 'sha256=%s\n' "$(combined "$go_version" "$ndk_version")"
            printf 'go=%s\nndk=%s\ntailpocket=%s\n' "$go_version" "$ndk_version" "$(sed -n 's/^.*jt-wang\/tailpocket \(v[^ ]*\).*$/\1/p' "$root/go/go.mod" | head -1)"
            artifacts | sed 's/^/artifact /'
        } > "$stamp"
        ;;
    check)
        [ -f "$stamp" ] || { echo "libs/tailcat/STAMP is missing; run libs/tailcat/build.sh" >&2; exit 1; }
        expected=$(combined "$(field go)" "$(field ndk)")
        if [ "$expected" != "$(field sha256)" ]; then
            echo "libs/tailcat/go changed since the prebuilt libraries were built; run libs/tailcat/build.sh and commit prebuilt/ and STAMP" >&2
            exit 1
        fi
        if [ "$(artifacts | sed 's/^/artifact /')" != "$(sed -n 's/^\(artifact .*\)$/\1/p' "$stamp")" ]; then
            echo "libs/tailcat/prebuilt does not match STAMP; run libs/tailcat/build.sh and commit prebuilt/ and STAMP" >&2
            exit 1
        fi
        echo "libs/tailcat prebuilt libraries match their sources"
        ;;
    *) echo "usage: stamp.sh write <go> <ndk> | check" >&2; exit 2 ;;
esac
