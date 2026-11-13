#!/usr/bin/env bash
set -euo pipefail
dmg=${1:?Usage: notarize-macos.sh file.dmg [report-directory]}
reports=${2:-$(dirname "$dmg")}
profile=${KINUVO_MACOS_NOTARY_PROFILE:?Set KINUVO_MACOS_NOTARY_PROFILE to a notarytool Keychain profile}
credentials=(--keychain-profile "$profile")
if [ -n "${KINUVO_MACOS_KEYCHAIN:-}" ]; then credentials+=(--keychain "$KINUVO_MACOS_KEYCHAIN"); fi
mkdir -p "$reports"
name=$(basename "${dmg%.dmg}")
result="$reports/$name.notarization.json"
status=0
xcrun notarytool submit "$dmg" "${credentials[@]}" --wait --timeout 30m --output-format json > "$result" || status=$?
cat "$result"
submission=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("id", ""))' "$result")
if [ -n "$submission" ]; then
  xcrun notarytool log "$submission" "${credentials[@]}" "$reports/$name.notarization-log.json" || echo 'Notarization log unavailable; use the submission ID to retrieve it later.' >&2
fi
if [ "$status" != 0 ] || ! python3 -c 'import json,sys; sys.exit(json.load(open(sys.argv[1])).get("status") != "Accepted")' "$result"; then
  echo "Notarization did not succeed. See $result; do not distribute this DMG." >&2
  exit 1
fi
xcrun stapler staple "$dmg"
xcrun stapler validate "$dmg"
spctl --assess --type open --context context:primary-signature --verbose=2 "$dmg"
