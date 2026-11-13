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

## macOS signing and notarization

New macOS desktop releases require Developer ID signing and Apple notarization.
The Release workflow imports the certificate into a temporary Keychain, publishes
only an accepted and stapled DMG, and deletes the Keychain afterward.

Configure these repository Actions secrets before pushing a release tag:

| Secret | Value |
|---|---|
| `MACOS_CERTIFICATE_P12_BASE64` | Base64 of a Developer ID Application certificate exported with its private key as `.p12` |
| `MACOS_CERTIFICATE_PASSWORD` | The `.p12` export password |
| `APPLE_ID` | The Apple Account email used for notarization |
| `APPLE_TEAM_ID` | The developer Team ID |
| `APPLE_APP_SPECIFIC_PASSWORD` | An app-specific password generated at account.apple.com |

For local packaging, set `KINUVO_MACOS_SIGN_IDENTITY` to the Developer ID identity
shown by `security find-identity -v -p codesigning`. Set
`KINUVO_MACOS_NOTARY_PROFILE` to a stored `notarytool` profile to also notarize;
`KINUVO_MACOS_KEYCHAIN` selects an explicit Keychain when needed.

Local development builds remain ad hoc signed unless a Developer ID identity is
selected. Existing unsigned downloads may still need **System Settings → Privacy &
Security → Open Anyway** after the first launch attempt. The separate CLI archive
is outside this desktop signing pipeline.

## Windows apps

Windows desktop apps remain unsigned. Choose **More info → Run anyway** in SmartScreen.

## Target native libraries

Desktop and bundled-runtime CLI packages filter nested jars to the target OS and
architecture, including LiteRT-LM, zstd and WebRTC. The universal `kinuvo.jar`
retains all platforms. Nested jars remain uncompressed for the executable jar
loader; upstream license notices remain intact. Skiko and Rive resolve host
artifacts; filtering also removes extra architectures inside those artifacts. Run `python3 scripts/test-package-natives.py` to
check the packaging filter.

## Downloaded desktop engines

Desktop packages omit the LiteRT-LM JNI payload and the voice worker's ONNX Runtime
and sherpa libraries. First model setup downloads pinned upstream artifacts through
`models`, checking exact size and SHA-256 before extraction. Engines live
under `<Server home>/engines/<engine-version-platform>` (`KINUVO_HOME` is respected
through the Server's home). Setup needs internet access and adds download latency;
subsequent uses work offline. Android and iOS packaging are unchanged. Building the
voice worker still fetches the pinned SDK for its C header, but ships no SDK dylibs.

The macOS worker uses `dlopen` with an absolute verified-store path, checks every
required inference symbol, and does not depend on `DYLD_*` environment variables.
Upstream's arm64 libraries are ad hoc signed without a Team ID. The worker is
signed with hardened runtime and its own `disable-library-validation` entitlement.
The desktop launcher also declares that entitlement because downloaded LiteRT JNI
runs in the JVM. Parent signing preserves the worker's separate entitlements.
[Apple's library-validation rules](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.cs.disable-library-validation)
require an exception for libraries without the executable's Team ID. SHA-256 pins
establish download integrity; receipts detect later size/mtime changes, not hostile
local tampering. Do not replace pins with runtime-fetched checksums.

When invoking packaging through `mise run exec`, set `JAVA_HOME` with `env` after
the task name so machine-level mise settings cannot replace the selected JBR.
Packaging rejects a runtime SDK older than Java 25.

Developer ID packaging preserves the worker exception and the JVM's native-loading
and JIT entitlements. Engine-load checks and notarization do not replace an
installation and feature check on a clean Mac. Verify a browser-downloaded DMG
with its normal quarantine intact, including voice and permission prompts.

## Documentation website

The docs are served as static assets by the `kinuvo-docs` Cloudflare Worker at
https://kinuvo.feernandooff.com. Build and deploy from the repository root:

```sh
mise run exec -- npm --prefix docs ci
mise run exec -- npm --prefix docs run build
mise run exec -- npm --prefix docs run deploy
```

`docs/wrangler.jsonc` owns the asset directory, custom domain and 404 behavior.
The documentation workflow deploys changes on main using the repository secret
`CLOUDFLARE_API_TOKEN`. Scope it to this Cloudflare account with Workers Scripts
Edit and to feernandooff.com with Zone Read and Workers Routes Edit. Wrangler's
interactive OAuth login is for local deployment; do not copy it into CI secrets.
The old `/kinuvo/` prefix redirects to root paths on the new domain. The legacy
GitHub Pages site remains available until its retirement is separately arranged.
