#!/usr/bin/env bash
set -euo pipefail
: "${RUNNER_TEMP:?}" "${GITHUB_ENV:?}" "${MACOS_CERTIFICATE_P12_BASE64:?}" "${MACOS_CERTIFICATE_PASSWORD:?}"
: "${APPLE_ID:?}" "${APPLE_TEAM_ID:?}" "${APPLE_APP_SPECIFIC_PASSWORD:?}"
umask 077
directory=$(mktemp -d "$RUNNER_TEMP/kinuvo-signing.XXXXXX")
keychain="$directory/signing.keychain-db"
certificate="$directory/certificate.p12"
trap 'rm -f "$certificate"' EXIT
password=$(openssl rand -hex 32)
# Save the cleanup path before creating or importing anything.
printf 'KINUVO_MACOS_KEYCHAIN=%s\n' "$keychain" >> "$GITHUB_ENV"
printf '%s' "$MACOS_CERTIFICATE_P12_BASE64" | base64 --decode > "$certificate"
security create-keychain -p "$password" "$keychain"
security set-keychain-settings -lut 21600 "$keychain"
security unlock-keychain -p "$password" "$keychain"
security import "$certificate" -k "$keychain" -P "$MACOS_CERTIFICATE_PASSWORD" -T /usr/bin/codesign
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$password" "$keychain" >/dev/null
identity=$(security find-identity -v -p codesigning "$keychain" | sed -nE 's/.*\) ([0-9A-F]+) "Developer ID Application:.*"/\1/p')
if [ -z "$identity" ] || [[ "$identity" == *$'\n'* ]]; then
  echo 'Import a .p12 containing exactly one valid Developer ID Application identity and its private key.' >&2
  exit 1
fi
xcrun notarytool store-credentials kinuvo-notary --keychain "$keychain" \
  --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" --password "$APPLE_APP_SPECIFIC_PASSWORD"
printf 'KINUVO_MACOS_SIGN_IDENTITY=%s\nKINUVO_MACOS_NOTARY_PROFILE=kinuvo-notary\n' "$identity" >> "$GITHUB_ENV"
