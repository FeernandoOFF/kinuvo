#!/bin/sh
# Checks the Notification Service Extension's Web Push decrypt on the Mac, without a simulator.
set -e
root=$(cd "$(dirname "$0")/../../.." && pwd)
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
{ echo 'import CryptoKit'; echo 'import Foundation'; cat "$root/apps/ios/tests/PushKeysTests.swift"; } > "$dir/main.swift"
xcrun swiftc "$root/apps/ios/notifications/PushKeys.swift" "$dir/main.swift" -o "$dir/tests"
"$dir/tests"
