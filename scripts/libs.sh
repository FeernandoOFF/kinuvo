#!/bin/sh
# Prebuilt native libraries live in GitHub Release assets, not git. Each libs/<name>/prebuilt.json pins them.
# Usage: libs.sh fetch              download missing or changed files into libs/*/prebuilt/
#        libs.sh publish <name>     after libs/<name>/build.sh: create the release, upload, rewrite prebuilt.json
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
tab=$(printf '\t')

sha() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

entries() { # prints "path<TAB>url<TAB>sha256" for each manifest file
    python3 -I -c 'import json, sys
for f in json.load(open(sys.argv[1]))["files"]:
    print("\t".join((f["path"], f["url"], f["sha256"])))' "$1" | tr -d '\r'
}

up_to_date() { # dir path sha
    case $2 in
        *.xcframework) [ -d "$1/prebuilt/$2" ] && grep -qxF "$3  $2" "$1/prebuilt/.fetched" 2>/dev/null ;;
        *) [ -f "$1/prebuilt/$2" ] && [ "$(sha "$1/prebuilt/$2")" = "$3" ] ;;
    esac
}

fetch_one() { # dir path url sha
    dir=$1 path=$2 url=$3 want=$4
    up_to_date "$dir" "$path" "$want" && return 0
    work=$(mktemp -d)
    trap 'rm -rf "$work"' EXIT
    echo "Fetching libs/$(basename -- "$dir")/prebuilt/$path"
    curl -fsSL --retry 3 -o "$work/asset" "$url"
    got=$(sha "$work/asset")
    [ "$got" = "$want" ] || { echo "sha256 mismatch for $url: expected $want, got $got" >&2; exit 1; }
    mkdir -p "$dir/prebuilt"
    case $path in
        *.xcframework)
            rm -rf "${dir:?}/prebuilt/$path"
            python3 -I -m zipfile -e "$work/asset" "$dir/prebuilt"
            { grep -vF "  $path" "$dir/prebuilt/.fetched" 2>/dev/null || true; printf '%s  %s\n' "$want" "$path"; } > "$work/state"
            mv "$work/state" "$dir/prebuilt/.fetched"
            ;;
        *)
            mkdir -p "$(dirname -- "$dir/prebuilt/$path")"
            mv "$work/asset" "$dir/prebuilt/$path"
            ;;
    esac
    rm -rf "$work"
    trap - EXIT
}

fetch() {
    for manifest in "$root"/libs/*/prebuilt.json; do
        [ -f "$manifest" ] || continue
        dir=$(dirname -- "$manifest")
        entries "$manifest" | while IFS="$tab" read -r path url want; do
            fetch_one "$dir" "$path" "$url" "$want"
        done
    done
}

publish() {
    name=${1:?usage: libs.sh publish <name>}
    dir="$root/libs/$name"
    [ -f "$dir/STAMP" ] || { echo "$dir/STAMP is missing; run libs/$name/build.sh" >&2; exit 1; }
    [ -d "$dir/prebuilt" ] || { echo "$dir/prebuilt is missing; run libs/$name/build.sh" >&2; exit 1; }
    "$dir/stamp.sh" sources
    stamp=$(sed -n 's/^sha256=//p' "$dir/STAMP")
    short=$(printf '%s' "$stamp" | cut -c1-12)
    tag="libs-$name-$short"
    repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
    if gh release view "$tag" >/dev/null 2>&1; then
        echo "Release $tag already exists; change the sources and rebuild, or delete the release first" >&2
        exit 1
    fi
    work=$(mktemp -d)
    trap 'rm -rf "$work"' EXIT
    mkdir "$work/assets"
    : > "$work/entries"
    (cd "$dir/prebuilt" && find . -mindepth 1 -maxdepth 1 -name '*.xcframework' -type d | sed 's|^\./||' | LC_ALL=C sort) | while read -r path; do
        asset="$path.zip"
        (cd "$dir/prebuilt" && zip -qry -X "$work/assets/$asset" "$path")
        printf '%s\t%s\t%s\n' "$path" "https://github.com/$repo/releases/download/$tag/$asset" "$(sha "$work/assets/$asset")" >> "$work/entries"
    done
    (cd "$dir/prebuilt" && find . -type f ! -name .DS_Store ! -name .fetched ! -path '*.xcframework/*' | sed 's|^\./||' | LC_ALL=C sort) | while read -r path; do
        asset=$(printf '%s' "$path" | tr '/' '-')
        cp "$dir/prebuilt/$path" "$work/assets/$asset"
        printf '%s\t%s\t%s\n' "$path" "https://github.com/$repo/releases/download/$tag/$asset" "$(sha "$dir/prebuilt/$path")" >> "$work/entries"
    done
    gh release create "$tag" "$work"/assets/* --prerelease --latest=false \
        --title "libs/$name prebuilt $short" \
        --notes "Kinuvo's prebuilt native libraries for libs/$name (stamp $stamp), fetched by scripts/libs.sh. Not a product release."
    python3 -I -c 'import json, sys
stamp, tag = sys.argv[1:3]
files = [dict(zip(("path", "url", "sha256"), line.rstrip("\n").split("\t"))) for line in sys.stdin]
print(json.dumps({"stamp": stamp, "tag": tag, "files": files}, indent=2))' "$stamp" "$tag" < "$work/entries" > "$dir/prebuilt.json"
    echo "Published $tag; commit libs/$name/prebuilt.json"
}

case "${1:-}" in
    fetch) fetch ;;
    publish) shift; publish "$@" ;;
    *) echo "usage: libs.sh fetch | publish <name>" >&2; exit 2 ;;
esac
