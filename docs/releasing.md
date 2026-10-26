---
title: "Releasing"
description: "Kinuvo releasing."
---

Push a `v*` tag; [the release workflow](../.github/workflows/release.yml) builds everything and publishes a GitHub release. Tags with a suffix (`v0.2.0-beta.1`) become prereleases.

```sh
git tag v0.1.0 && git push origin v0.1.0
```

| Asset | Built by |
|---|---|
| `kinuvo.jar` | `scripts/package.sh jar`: the Server and CLI with the web app, for any Java 25+ |
| `kinuvo-<os>-<arch>.tar.gz` / `.zip` | `scripts/package.sh cli`: the same jar with a bundled runtime, for `install.sh` / `install.ps1` |
| `Kinuvo-desktop-macos-arm64.dmg`, `Kinuvo-desktop-linux-<arch>.deb`, `Kinuvo-desktop-windows-x64.zip` | `scripts/package.sh desktop` |
| `Kinuvo-android.apk` | bundletool, from the release AAB |
| `ghcr.io/<repo>:<version>` | the [Dockerfile](../Dockerfile), linux/amd64 and linux/arm64 |
| `SHA256SUMS` | checksums of all of the above, with build provenance attestations |

`scripts/package.sh` runs locally too, for this machine's OS and architecture; set `JAVA_HOME` to a JetBrains Runtime 25 SDK.

## Android signing

Generate the key once and keep it backed up; installed apps only accept upgrades signed with the same key.

```sh
keytool -genkeypair -keystore release.keystore -alias kinuvo -keyalg RSA -keysize 4096 -validity 10000
base64 -i release.keystore | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS --body kinuvo
gh secret set ANDROID_KEY_PASSWORD
```

Without these secrets the release skips the APK.

Android release builds use the tag without `v` as `versionName` and the Release workflow's run number as `versionCode`. Each new run increases the code; reruns keep it. Local builds default to `0.1.0-dev` with code `1`.

## Unsigned macOS and Windows apps

The desktop apps are not code-signed yet. On macOS, open the app once, then allow it under **System Settings → Privacy & Security → Open Anyway**. On Windows, choose **More info → Run anyway** in SmartScreen. The CLI installed by `install.sh` is unaffected.
