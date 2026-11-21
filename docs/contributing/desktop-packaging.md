---
title: Desktop packaging and updates
description: Proposed desktop distribution architecture using Amper outputs and Nucleus packaging.
---

# Desktop packaging and updates

Status: proposed; no packaging or updater implementation is included in this document. The base is [#229](https://github.com/FeernandoOFF/kinuvo/pull/229), `spike/nucleus-webview`, inspected at `e81b4924222620c93ed491e54f8d90481f4d1adc`. Research date: 21 November 2026. Builds, packaging, app launches and installer tests were deliberately excluded from this research phase.

## Recommendation

Keep Kotlin Toolchain (Amper) as the compiler. Add an isolated, packaging-only Gradle project using Nucleus's plugin, consuming files produced by Amper. Add `dev.nucleusframework:nucleus.updater-runtime:3.0.0-dev-202611161221` to the desktop Amper module, matching the Tao dependencies in #229. Use public GitHub Releases for installers and update metadata.

Auto-update is the first acceptance criterion: an installed version must discover, download, authenticate and install the next version, preserve the owner's data and service settings, and relaunch successfully on each supported OS. Producing installers alone does not complete this work. Publisher authentication and background Server coordination are release gates described below.

This recommendation is based on source inspection, not a proven Amper-to-Nucleus packaging integration. Resolve the bridge in a small implementation step before replacing the existing release path. Do not upgrade unrelated Nucleus, Compose or Kotlin versions to match examples on the website.

## Current repository behavior

- `scripts/package.sh desktop` invokes `./kotlin package -m desktop -p jvm -f executable-jar`, copies `build/tasks/_desktop_executableJarJvm/desktop-jvm-executable.jar`, restores dependency filename collisions, prunes foreign natives and adds the native-access manifest flag. This is a Spring Boot **nested executable JAR**, not a flattened classpath. Nested dependency JARs must remain STORED.
- A JBR 25+ SDK supplies `jlink` and `jpackage`; an explicit module list covers service-loaded dependencies. macOS first creates an app-image, inserts the launch-agent plist and privacy text, signs the bundle and creates a DMG. Linux produces a DEB. Windows produces an app-image ZIP, with no installer upgrade identity. There is no desktop update feed or updater integration.
- macOS bundles `libKinuvoSpeech.dylib`, `libKinuvoApple.dylib`, `libKinuvoService.dylib`, Tailcat and `voice-worker`. `scripts/build-mac-*.sh` compile Swift libraries with local ad-hoc signatures. `scripts/sign-macos.py` replaces these with release signatures, recursively signs Mach-O libraries inside nested JARs, then signs loose binaries, frameworks, runtime and enclosing app. Voice inference has separate entitlements. Preserve this nested-JAR coverage.
- `scripts/run-desktop.sh` uses packaged `dist/Kinuvo.app` on macOS for TCC ownership, except its Compose hot-reload path. `scripts/launch-desktop.py` launches through LaunchServices, forwards the assigned home/port and tracks that launch for termination. Other platforms use the toolchain run command. Development launches must never update themselves from the production feed.
- `apps/desktop/service/ServiceAgent.swift` is a dylib bridge to `SMAppService.agent`, not a separate login helper executable. `dev.kinuvo.server.plist` starts `Contents/MacOS/Kinuvo --serve` with `KeepAlive`. `LoginService.kt` loads the dylib using `compose.application.resources.dir` and preserves the Server's configured home.
- `.github/workflows/release.yml` builds voice workers, the portable Server JAR, CLI archives, desktop artifacts and Android. Desktop runners cover Linux x64/arm64, macOS arm64 and Windows x64. macOS imports a Developer ID certificate into a temporary keychain, probes signing, stores notary credentials, packages/notarizes and cleans up. The release job gathers artifacts, generates SHA256SUMS, attests public releases and publishes tags as releases/prereleases. No Windows/Linux signing is configured there.
- `.github/workflows/ci.yml` checks packaging Python helpers and shell syntax; JVM/UI suites and native voice checks do not prove installed application upgrades.

## What Nucleus provides

Nucleus's packaging plugin runs `jpackage` for an app-image and electron-builder in `--prepackaged` mode for installer creation. Node/npm are build dependencies, not an Electron runtime shipped with Kinuvo. Its task provisions a pinned electron-builder toolchain, generates configuration and launcher metadata and handles format-specific layouts. [Packaging overview](https://nucleusframework.dev/en/docs/packaging/), [packager source](https://github.com/NucleusFramework/Nucleus/blob/67e9920f171a6d569e520d95b53c8e594b44a4d4/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/desktop/application/tasks/AbstractElectronBuilderPackageTask.kt).

The runtime is Nucleus's own JVM updater, **not Sparkle, WinSparkle or electron-updater**. Its Maven artifact depends on core-runtime, Kotlin, coroutines and serialization; it needs no Gradle runtime. The exact updater POM and sources for #229's version are published on [Maven Central](https://repo.maven.apache.org/maven2/dev/nucleusframework/nucleus.updater-runtime/3.0.0-dev-202611161221/). The matching `dev.nucleusframework` [Gradle plugin marker](https://plugins.gradle.org/m2/dev/nucleusframework/dev.nucleusframework.gradle.plugin/3.0.0-dev-202611161221/dev.nucleusframework.gradle.plugin-3.0.0-dev-202611161221.pom) also exists. Publication is verified; dependency resolution and execution in this repository are not.

The [DSL reference](https://nucleusframework.dev/en/docs/reference/gradle-dsl/) exposes `disableDefaultConfiguration()`, `fromFiles(...)`, `mainJar`, `mainClass` and `javaHome`. Its formats include raw app-images; macOS DMG/ZIP/PKG; Windows EXE, MSI, NSIS, NSIS Web, Portable and AppX; Linux DEB, RPM, AppImage, Pacman, Snap and Flatpak; ZIP/TAR/7z archives. AppX can be combined into an MSIX bundle in CI. Format availability does not imply self-update support.

The [updater documentation](https://nucleusframework.dev/en/docs/packaging/auto-update/) describes electron-builder-compatible YAML with `version`, `files` (URL, base64 SHA-512, size) and `releaseDate`. Metadata names are `latest-mac.yml`, `latest.yml` and `latest-linux.yml`, with channel equivalents. Supported direct updates include macOS ZIP (DMG for initial installation), Windows NSIS/MSI and Linux AppImage/DEB/RPM. Stores own updates for store formats. Differential downloads use content block maps and HTTP ranges, with a full-download fallback; no pairwise delta packages are needed. NSIS and macOS archives use adjacent blockmaps, AppImage embeds its map. DEB/RPM should initially be budgeted as full downloads.

Documentation pages currently mix 2.1/2.5 examples. Source findings below distinguish the published `3.0.0-dev-202611161221` updater from the inspected upstream main snapshot `67e9920f171a6d569e520d95b53c8e594b44a4d4`. Pin all artifacts/actions and confirm plugin task names and hooks against the matching plugin before implementation.

## Integration choices with Amper

| Approach | Feasibility and ownership |
| --- | --- |
| Runtime dependency + existing scripts | Feasible. We must own installer generation, feed merging, launcher properties, signing, blockmaps and upgrade layouts. Useful fallback if the Gradle bridge fails. |
| Invoke Nucleus packager JAR directly | No supported standalone Nucleus packager CLI was found. The inspected packager is an injected Gradle task. Do not invoke internal task classes as a Java main. |
| Invoke electron-builder directly | Its CLI supports prepackaged inputs, but Nucleus wraps substantial layout/configuration logic. Direct use duplicates those responsibilities, especially versioned Windows installs. |
| Packaging-only Gradle project | Recommended. Supported file-input DSL is the narrow boundary; no Kotlin source compilation or second dependency graph for application code. Prove nested executable-JAR support first. |

The current [Kotlin Toolchain JVM application guide](https://github.com/JetBrains/kotlin-toolchain/blob/a4f1d664ba3bf0dce94870af6052f73bfd9986c9/docs/src/user-guide/product-types/jvm-app.md) documents regular and executable JAR packaging. The [plugin task API](https://github.com/JetBrains/kotlin-toolchain/blob/a4f1d664ba3bf0dce94870af6052f73bfd9986c9/docs/src/user-guide/plugins/topics/tasks.md) can consume runtime classpaths and module JARs, declare input/output tasks and expose commands. Its [overview](https://github.com/JetBrains/kotlin-toolchain/blob/a4f1d664ba3bf0dce94870af6052f73bfd9986c9/docs/src/user-guide/plugins/overview.md) still lists packaging/publishing plugin integration as future work. No native installer/updater equivalent is documented. A custom toolchain task could orchestrate packaging later; keep shell orchestration initially. Leave the vendor-managed `./kotlin` wrapper unchanged.

Proposed packaging project: `packaging/desktop/`, with a pinned wrapper/plugin and its own output directory. `scripts/package.sh desktop` remains the entry point and stages Amper output, then invokes that project. Preserve `jar` and `cli` paths. Use file input with default source-set configuration disabled. Read the executable JAR's `Main-Class` and `Start-Class`; the launcher must enter the Boot loader, not assume `kinuvo.desktop.MainKt` is at the JAR root. Retain collision restoration, target-native pruning and required JVM modules. If that input is unsupported, export a group-qualified flat runtime classpath from Amper rather than adding Gradle source compilation.

## Artifacts and hosting

| OS | Initial artifacts | Update behavior and scope |
| --- | --- | --- |
| macOS arm64 | Signed/notarized DMG + ZIP of the same finalized `Kinuvo.app`, plus blockmaps | ZIP replaces the installed app at its existing path; DMG is first install. Add Intel only after its complete native dependency set is available. |
| Windows x64 | Signed per-user NSIS `-nsis.exe` + blockmap | Prefer per-user updates without UAC. Keep stable app ID, install path and upgrade identity. MSI may follow for managed deployment; omit MSIX initially. |
| Linux x64/arm64 | AppImage, DEB and RPM; signatures/public key | AppImage replaces the writable executable; DEB/RPM normally invoke polkit and may prompt. Do not describe these as universally silent. |

Name every file with full version, OS, architecture and, where necessary, format. Preserve `kinuvo-desktop` as the Linux package name. Explicitly set executable type so AppImage installations cannot accidentally select DEB updates. Published updater source falls back to the first format candidate when no architecture matches: reject missing architecture coverage before download, and never publish a feed with only foreign-architecture payloads for an enabled platform.

Use `GitHubProvider(owner = "FeernandoOFF", repo = "kinuvo")` without an embedded token for public releases. The published 3.0 provider supports public GitHub releases and prerelease channels; its APIs differ from older examples. Publish one complete YAML per OS/channel with all supported architectures, never overwrite it with the last runner's file. Match metadata version to the full tag version without `v`; keep the prerelease suffix for updater ordering even where installer version fields must be numeric.

For existing `v0.1.0-rc.*` releases, explicitly implement and test an `rc` channel rather than assuming beta mapping. Stable clients must not be offered prereleases. Assets, signatures, maps and metadata go into a draft GitHub Release first; validate all references/hashes/architectures, then publish the whole release. Do not mutate signed payloads afterward. [Publishing](https://nucleusframework.dev/en/docs/packaging/publishing/).

Existing installations have no updater: their first migration is a manual install. Document Windows ZIP-to-NSIS migration and avoid leaving two launch targets. Do not claim updates of the separate CLI or portable Server JAR.

## Update lifecycle and release gates

Add a desktop updater adapter in the desktop host. Shared immutable update state and user actions belong in `client/app`; rendering belongs in `client/ui`. Keep Nucleus/JVM APIs out of shared feature logic. Check on startup with backoff and periodically, plus a manual Settings action. Download in the background with progress, retry/cancel and clear errors. Offer an explicit restart to install; defer while Voice chat or local Server work is active. The headless `--serve` and `--service` paths must not initialize the UI updater.

Set stable `NucleusApp` identity/version and full SemVer explicitly, packaging format markers and an isolated update cache. Disable installation in unpackaged/development runs. Keep `allowLaunchOverrides = false` in release builds. Serialize installation across windows/processes: #229 explicitly disables Nucleus single-instance mode.

**Authenticate before installation.** The published updater verifies payload SHA-512 against downloaded metadata. That detects corruption, but metadata from the same compromised origin cannot authenticate a publisher. Its macOS ZIP script swaps bundles and removes quarantine without a `codesign`/Team ID validation step. Its AppImage replacement also needs a publisher-authentication policy. Before enabling production updates, require verification of a detached signature using an embedded public key for every downloaded artifact, plus macOS expected Team ID/bundle ID and Windows expected Authenticode publisher checks. Fail closed; never fall back to an unsigned update. Establish an upstream verification hook or a narrowly scoped adapter/fix before installation, including differential downloads. Signing the release in CI alone does not implement these runtime checks. Choose and test key rotation before retiring the old key.

**Coordinate the login Server.** Preserve `dev.kinuvo.desktop`, `dev.kinuvo.server.plist`, its associated bundle identifier and `Contents/MacOS/Kinuvo --serve`. Retain the Swift service bridge; Nucleus launch-agent DSL is packaging support, not a replacement for SMAppService approval/status. Keep its resources path compatible or change the loader alongside packaging. Before replacing the app, record whether the service was enabled, unregister it to prevent KeepAlive respawn, drain and stop only this installation's Server/voice worker, then install. Restore registration/settings on successful relaunch and recover them on failure. Requires-approval and disabled states must stay distinct. No other home or checkout's Server may be stopped. macOS bundle replacement alone does not prove the service has adopted the new runtime.

Windows 3.0 updater sources include hot updates for the plugin's versioned per-user NSIS layout. Do not assume this is safe for Kinuvo's embedded Server: a new UI must not start a second Server on the same home/port during handoff. Initially require controlled shutdown/handoff; prove the supported installer path with the exact plugin. Do not advertise seamless hot updates before that evidence exists.

## Native payloads, signing and platform requirements

Stage the target's Tailcat library and licenses, voice-worker tree, speech helpers and all runtime resources before signing. Include Tao, WebView, Skiko, JNA and other native dependencies from the resolved classpath. Inspect the actual archive; a filename filter alone is not an ABI inventory. Preserve executable permissions and relative library paths. Linux arm64 voice-worker support is currently limited by the script's platform gate; either supply it or document that capability gap, not an architecture-independent feature promise.

On macOS, retain recursive signing of Mach-O entries inside nested JARs; ordinary bundle signing cannot reach them. Apply separate voice-worker entitlements, JIT/native-access flags, speech/microphone/calendar/reminder usage text and hardened runtime settings. Complete plist/resource mutations before final signatures. Sign inside out, verify recursively, notarize and staple the finalized app before archiving the ZIP; sign/notarize/staple the DMG as required. Generate metadata and blockmaps only after all byte-changing steps. Verify Gatekeeper acceptance of an extracted update ZIP as well as the DMG. [macOS packaging](https://nucleusframework.dev/en/docs/packaging/macos/), [signing](https://nucleusframework.dev/en/docs/packaging/code-signing/).

On Windows, sign the launcher, shipped helper executables/DLLs and final NSIS installer with timestamping. Verify `KinuvoSpeech.exe`, native voice dependencies and WebView2 availability on a clean machine; the runtime prerequisite/bootstrap policy must be settled. [Windows packaging](https://nucleusframework.dev/en/docs/packaging/windows/).

On Linux, validate Tao/WebKitGTK, audio and glibc requirements on the oldest supported distribution and declare DEB/RPM dependencies. AppImage does not guarantee that system WebKitGTK/FUSE dependencies disappear. Start with normal polkit authorization for DEB/RPM. Nucleus offers a signed-package, root-owned helper with scoped polkit rules for passwordless installs; defer that extra privileged surface until its signature, package-name and permission checks are reviewed. [Linux packaging](https://nucleusframework.dev/en/docs/packaging/linux/).

Use direct distribution initially. Nucleus's store pipeline extracts natives and rewrites paths for sandboxed formats; Kinuvo's Computer control, exec tools, background Server and permissions need separate store feasibility work. [Sandboxing](https://nucleusframework.dev/en/docs/packaging/sandboxing/).

Keep JBR/HotSpot for this migration. Nucleus modules are independently consumable; Tao does not require GraalVM. Disable AOT cache generation initially: it runs a training app, must use disposable data, and changes packaged bytes. Optimize after update correctness. [Modules](https://nucleusframework.dev/en/docs/concepts/modules/), [runtimes](https://nucleusframework.dev/en/docs/concepts/runtimes/), [AOT cache](https://nucleusframework.dev/en/docs/performance/aot-cache/).

Public GitHub TLS needs no private CA. If enterprise trust is required, Nucleus supports imported certificates in a copied jlink runtime or `native-http` using OS trust. Never disable TLS verification or commit private certificates. [Trusted certificates](https://nucleusframework.dev/en/docs/packaging/trusted-certificates/).

## Credentials to provision

These are required inputs for implementation/release, not requests to disclose credentials in chat:

| Purpose | Secrets / non-secret configuration |
| --- | --- |
| macOS release signing | Existing `MACOS_CERTIFICATE_P12_BASE64`, `MACOS_CERTIFICATE_PASSWORD`; Developer ID Application private key/certificate. Reuse ephemeral keychain setup and cleanup. |
| macOS notarization | Existing `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_SPECIFIC_PASSWORD`; alternatively App Store Connect `.p8`, key ID and issuer ID, using one authentication mode only. |
| Windows signing | Choose certificate/PFX plus password in new CI secrets, or Azure Artifact Signing account/profile/endpoint/publisher and federated identity. Provision a provider compatible with the certificate's hardware-key requirements. Costs are independent of Nucleus. |
| Linux package signing | New protected GPG private key, passphrase and configured fingerprint; publish only public key and signatures. |
| Cross-platform update authentication | Protected update-signing private key; embedded public key and key ID, with rotation policy. Select the signature scheme/hook during the implementation gate. |
| Publishing | Job-scoped `GITHUB_TOKEN` with `contents: write`; no long-lived GitHub credential distributed in the app. |

Nucleus's authored code is MIT with separate third-party notices; it has no packaging subscription. Keep redistributed licenses, including Tailcat/TailPocket. [License](https://github.com/NucleusFramework/Nucleus/blob/67e9920f171a6d569e520d95b53c8e594b44a4d4/LICENSE).

## CI changes and implementation sequence

1. Prove the packaging bridge on one OS using the exact plugin version and prebuilt Amper JAR. Verify Boot entry point, resources, modules, app ID and native loading. No application sources/dependency versions duplicated in Gradle. If file inputs fail, evaluate exported flat classpaths before direct electron-builder tooling.
2. Add the matched updater dependency and shared update state. Implement version/channel/architecture/format selection and the authenticated-install gate. Cover corrupt downloads, invalid signatures, missing architecture, prereleases, cancellation and offline checks with focused tests.
3. Split desktop assembly into staging and final packaging while preserving CLI/JAR behavior. Stage natives, service files and privacy settings, then sign once at the correct boundary. Configure DMG/ZIP, NSIS, AppImage/DEB/RPM with stable identities and pinned tool versions.
4. Implement service/embedded Server shutdown and recovery across install success, failure and interrupted relaunch. Preserve owner homes and settings. Prove disabled and requires-approval service states and multiple UI instances.
5. Extend release desktop jobs on matching runners, retain JBR provisioning and ephemeral macOS signing. Install Node/Gradle and only required OS packaging tools. Keep caches/output directories separate from Amper. Do not copy Nucleus's setup action wholesale: its default JDK choices differ and its Linux setup starts Xvfb. Packaging without AOT training should not require an added virtual display. Inspect and pin any reused [Nucleus CI actions](https://nucleusframework.dev/en/docs/packaging/ci-cd/).
6. In the release job, collect payloads/maps/signatures, merge one YAML per channel/OS, validate architecture completeness and hashes, then generate checksums/provenance. Upload to a draft release and publish only when every required platform succeeds. Preserve other release products. Secret-bearing work runs only in trusted release contexts; forks get unsigned packaging checks.
7. Extend the existing CI packaging job with bridge/config validation and feed validation; add scoped installer smoke jobs on each OS. Keep source/JVM tests distinct from installed updater verification. Use disposable Kinuvo homes and assigned ports. Once implementation starts, run existing Compose UI flows before repeatable app verification as required by AGENTS.md.
8. Perform installed N → N+1 upgrades on all supported OS/architectures, then another update to exercise cached differential downloads. Test fresh install, full fallback, interrupted install, insufficient disk/permissions, bad signature, missing feed, active voice/Server, restart failure and data preservation. Windows and Linux need their own runtime evidence. Publish a prerelease only after those gates pass; document one-time manual migration and recovery before enabling stable checks.

Do not promise automatic rollback of data migrations. macOS script swap recovery is not application/database health rollback. Releases must preserve a recoverable previous payload and define compatible schema migration/backup behavior before attempting rollback after a launch failure.

## Alternatives

| Option | Three-OS updates and Amper effort | Cost/license |
| --- | --- | --- |
| Nucleus packaging bridge + updater | One runtime API, GitHub YAML, all target OSes; medium integration effort plus authentication/service gates. Fits the Tao migration. | MIT authored code, third-party notices; signing costs remain. |
| Hydraulic Conveyor | Standalone packager consumes JVM inputs; native update mechanisms (Sparkle/macOS, Windows package updates, Linux apt). Strong fallback if Nucleus integration is unreliable; Linux behavior follows package-manager policy. Requires its generated update-site layout, not just release assets. | Proprietary; free for OSI open-source projects, otherwise currently $45/month standard, three apps, excluding VAT. Confirm Kinuvo eligibility. [Pricing](https://hydraulic.dev/pricing.html), [FAQ](https://conveyor.hydraulic.dev/15.0/faq/), [update modes](https://conveyor.hydraulic.dev/18.1/configs/update-modes/). |
| jpackage + Sparkle / WinSparkle | Retains scripts, but adds native bridges and appcasts for macOS/Windows, plus an independent Linux package/update solution. Highest platform-specific maintenance. | Open source; Sparkle permissive license and third-party notices, WinSparkle current MIT. No updater subscription. [Sparkle](https://sparkle-project.org/documentation/), [license](https://github.com/sparkle-project/Sparkle/blob/2.x/LICENSE), [WinSparkle](https://winsparkle.org/), [license](https://github.com/vslavik/winsparkle/blob/master/COPYING). |
| update4j | Easy Maven integration and signed configuration/files, but we own bootstrap, JRE/native replacement, OS installers and service lifecycle. Poor fit for replacing signed whole apps. | Apache-2.0; repository archived March 2024. [Repository](https://github.com/update4j/update4j), [security model](https://github.com/update4j/update4j/wiki/Documentation). |

## Decisions needed before production release

- Is the packaging-only Gradle boundary acceptable? The recommendation keeps all compilation in Amper.
- Is initial coverage macOS arm64, Windows x64 and Linux x64/arm64 sufficient, with Intel macOS/Windows arm64 deferred until native dependencies are ready?
- Should update downloads be automatic with an explicit restart prompt, and should `rc` remain the prerelease channel?
- Which Windows signing provider and update-signature key custody/rotation policy should CI use?
- What minimum Linux distributions and WebView2 prerequisite policy are supported? Are polkit prompts acceptable for DEB/RPM, with writable AppImage as the first no-root update path?
