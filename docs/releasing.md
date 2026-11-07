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

## Google Play

The `android-play` module is the Play distribution. It supports Android 8.0 (API 26) and newer,
targets API 36, and uses `com.feernandooff.kinuvo`; the sideloaded `android` app uses `com.feernandooff.kinuvo.full`, so both can be installed. It excludes the
Android Computer implementation, Accessibility Service, secure-settings and
screen-wake permissions, and installed-app discovery. Computer use is absent from
This Device's supported tools. The directly distributed `android` app retains it
through the optional `android-computer` module. Launcher code and common resources
are shared using relative symlinks.

Build the release bundle and sign it with a local upload key:

```sh
mise run kotlin -- package -m android-play -f aab
mise run exec -- python3 scripts/sign-android-play.py build/tasks/_android-play_bundleAndroid/gradle-project-release.aab --init-upload-key
```

Use the AAB path printed by the package command if it differs. Omit
`--init-upload-key` on subsequent releases. The script writes `dist/Kinuvo-play.aab`
and its public upload certificate, `dist/Kinuvo-play-upload.pem`. Back up the
ignored `signing/` directory securely: it holds the upload keystore and its random
password, created with owner-only permissions. Never commit these files.

Choose Google's generated app signing key when enrolling the new app in Play App
Signing. Google signs the APKs delivered to users; the local upload key signs the
AAB submitted to Play. Google-managed signing does not eliminate upload signing.
The Play and sideloaded builds are separate apps with separate data; neither
upgrades the other.

Before each upload, set a monotonically increasing `versionCode` and release
`versionName` in `apps/android-play/module.yaml`; the GitHub APK workflow does not
set these values for the Play module. Store declarations and review, including
foreground service and AI/privacy requirements, remain separate from packaging.

## Unsigned macOS and Windows apps

The desktop apps are not code-signed yet. On macOS, open the app once, then allow it under **System Settings → Privacy & Security → Open Anyway**. On Windows, choose **More info → Run anyway** in SmartScreen. The CLI installed by `install.sh` is unaffected.

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
`harness/models`, checking exact size and SHA-256 before extraction. Engines live
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

Release signing remains ad hoc. Developer ID signing, Gatekeeper quarantine and
notarization are not proven by local engine-load checks; a future notarized release
must preserve the worker exception and the JVM's native-loading entitlements and
verify installation on a clean Mac.

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
