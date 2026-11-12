# Kinuvo for iOS

Thin SwiftUI shell for the shared Compose UI, with an opt-in native navigation integration. Runs a foreground Server on-device or connects to a remote Server. The app requires iOS 26 or later (on-device speech, background runs and the Apple model use iOS 26 APIs) and runs on iPhone only; on-device inference also needs an Apple Intelligence-capable device.

Agent avatars are Rive animations on Android and desktop; iOS shows their still PNG instead, so it needs no Rive framework.

From the repository root:

```sh
./kotlin run -m ios -p iosSimulatorArm64
```

For an iPhone, open `apps/ios/module.xcodeproj` in Xcode, select the `ios` scheme and your connected iPhone, and run. Automatic signing is configured for Fernando's personal development team; change Signing & Capabilities to use another team. Trust the computer and enable Developer Mode on the phone if prompted. The Build Kotlin phase runs the repository's Kotlin Toolchain.

Or deploy to a connected device from the command line:

```sh
xcrun devicectl list devices
./kotlin run -m ios -p iosArm64 --device-id <device-id>
```

The project uses Kotlin Toolchain 0.13.0 with incremental compilation enabled. Debug native linking reuses compiler caches; compilation to `.klib` files still runs in full.

Debug builds keep `ENABLE_DEBUG_DYLIB=NO` in the Xcode target. This works around unsigned debug dylibs observed with Kotlin Toolchain 0.12.2 during CLI device installs, which iOS refuses to load. Keep this setting when regenerating the Xcode project. The Debug target also defines `DEBUG` in `SWIFT_ACTIVE_COMPILATION_CONDITIONS` for the simulator test override; preserve it when regenerating.

## TestFlight builds

Bump `CURRENT_PROJECT_VERSION` for each upload, then archive and export from the repository root:

```sh
_JAVA_OPTIONS=-Xmx10g xcodebuild -project apps/ios/module.xcodeproj -scheme ios -configuration Release \
  -destination generic/platform=iOS -archivePath build/Kinuvo.xcarchive -allowProvisioningUpdates _JAVA_OPTIONS=-Xmx10g archive
xcodebuild -exportArchive -archivePath build/Kinuvo.xcarchive -exportOptionsPlist apps/ios/store/ExportOptions.plist -exportPath build/export -allowProvisioningUpdates
```

`apps/ios/store/ExportOptions.plist` sets `method` to `app-store-connect`, `destination` to `export` and `teamID`. Inspect the exported archive/IPA, then upload through Xcode Organizer or Transporter. The release framework's link needs more than the 3 GB heap the Kotlin Toolchain gives the native compiler; `_JAVA_OPTIONS` is the one JVM option that overrides it, passed as a build setting so the Build Kotlin phase sees it. Xcode must be signed in to the team's Apple account.

## On-device demo

The embedded Server is selected on a fresh install. If this app already remembers a remote Server, select **Embedded** in the Server menu. No pairing code, cloud login or API key is needed. The model picker shows **Apple on-device model**, and **Apple Private Cloud Compute** when the build includes it (below).

Use a physical Apple Intelligence-capable device (iPhone 15 Pro/Pro Max or a supported newer iPhone). Enable Apple Intelligence in Settings and wait for its model download to finish. The simulator can verify the UI and unavailable-model messages; it does not prove local inference works.

1. Install and launch on the device using Xcode or the device command above.
2. Enable airplane mode and keep Kinuvo in the foreground.
3. Send “Remember that I prefer tea to coffee.” Check that the memory tool succeeds before the Agent says it saved the preference.
4. Start a new Session and ask “What drink do I prefer?”
5. Relaunch the app and repeat the question in another new Session to verify persistence.

The same core `AgentRun`, file repositories and Approval policy run on-device. Apple's Foundation Models stream replies and generate structured Memory requests; the harness executes the requests and supplies their results on the next model turn. Agents, Sessions, Memory, User Notes and Trust rules live in the app's Application Support/Kinuvo directory. Starting a new Session preserves the Agent's Memory and User Notes.

Apple's model can use up to six tools per turn, Memory and web search first (1,200 characters each for Memory and User Notes). It compacts longer conversations, though its small context window may still require a new Session. For full tool use, pick ChatGPT / Codex in the model picker after signing in under Settings → Model providers; the sign-in is kept in the iOS Keychain. Remote MCP servers, Firecrawl web search, ntfy notifications, Scripts, Knowledge (Markdown or Anytype), Tasks (including Apple Reminders), Apple Reminders and Calendar tools, and this Device's tools work on-device. Attachments, Skills, Routines, local (stdio) MCP and shell tools require a remote Server. A run you start keeps going after you leave the app, shown as a Live Activity you can cancel (iOS 26 continued processing), or for iOS's short grace period when it refuses one; runs waiting for an Approval or an answer stop. This Server does not accept other Devices or run scheduled work.

On unsupported devices or before the model is ready, sending a message shows the reason and setup guidance. You can still connect to a remote Server.

### Apple Private Cloud Compute

On iOS 27, Apple's larger server model (32K context, same privacy guarantees, a daily limit per iCloud account) can run Agents too. It shows as **Apple Private Cloud Compute** in the model picker once the build includes it. The on-device model's context window is read from the device (4,096 or 8,192 tokens).

1. Apply for Private Cloud Compute access at developer.apple.com/private-cloud-compute.
2. Once granted, add `com.apple.developer.private-cloud-compute` (`true`) to `apps/ios/Kinuvo.entitlements`.
3. Add `KINUVO_PCC` to the target's Active Compilation Conditions (`SWIFT_ACTIVE_COMPILATION_CONDITIONS`).
4. Build with Xcode 27 (iOS 27 SDK), e.g. `DEVELOPER_DIR=/Applications/Xcode-27.0.0.app/Contents/Developer ./kotlin run -m ios -p iosArm64 --device-id <id>`.

Without the entitlement, Private Cloud Compute reports itself available but stops the app on its first request, so the model stays out of builds that don't set `KINUVO_PCC`.

## Voice

Dictation is the microphone in the composer; Voice chat is the button beside it when the composer is empty. Device speech processes recognition and synthesis on-device and asks for microphone and Speech Recognition access. When the connected Server offers speech services, the app can instead send recorded audio for Server transcription and text for synthesis. Live voice streams microphone audio to the Server and its configured speech-to-speech provider, which can also receive Session history. Server speech paths request microphone access without Device Speech Recognition access. The text-message provider consent prompt does not currently gate Server transcription or Live voice; those external-processing paths remain a release blocker. The prompt is not Server-wide: Routines, Reflection (potentially using another provider), and requests from other paired Devices or desktop/web clients also run outside this Device’s prompt. Allowing a generic unknown-provider prompt is remembered for later unknown-provider sends. Replies come from the Session's model as usual. Device Voice chat keeps the microphone open while it speaks, with Apple's echo cancellation, so you can talk over a reply to cut it short; Pause stops it and the microphone until Resume. Premium and enhanced voices downloaded in Settings → Accessibility → Spoken Content are used when available. The simulator has no echo cancellation; use a device.

## Remote Server

In the desktop app, open the Server menu and choose **Pair a phone**. For a headless Server, run `kinuvo pair` on that machine (`kinuvo pair --manage` also grants Server configuration and Approvals). Open the iOS link or enter the advertised address and pairing code in the iOS app. Codes expire after ten minutes and work once.

With `--tailscale`, use the advertised `https://<machine>.<tailnet>.ts.net` address and connect the iPhone to the same tailnet. HTTPS and secure WebSockets use Apple's networking engine. See [browser access](../../docs/browser-access.md) for the Server setup. For direct private-network addresses, use the same Wi-Fi or VPN and allow local-network access when iOS asks. `localhost` on an iPhone refers to the phone, so use the Server machine's address. The simulator can reach a Server on the Mac through `127.0.0.1`.

Pairing is remembered across app launches. The shared client reconnects while the app is active; remote runs remain on the Server in the background.

## Push notifications

Notifications from a remote Server arrive through APNs and the Push relay ([ADR 0020](../../docs/adr/0020-push-relay.md)). The app registers for remote notifications on launch and asks for Notifications when onboarding finishes or a chat first opens; once they're allowed, it subscribes on each connected Server with `…/apns/sandbox/<token>` (Debug) or `…/apns/production/<token>` (Release). Its Web Push keys stay in the Keychain group `<team>.com.feernandooff.kinuvo.shared`. The **notifications** target, a Notification Service Extension (`com.feernandooff.kinuvo.notifications`), decrypts each push in Swift with CryptoKit (`notifications/PushKeys.swift`) and shows its title and body; it links no Kotlin, to stay within the extension's memory limit. Tapping one opens its Session on the Server that sent it, switching Servers when another one is selected. Approvals and questions are time-sensitive. Mobile test runs don't ask or subscribe.

The Apple Developer portal needs Push Notifications and Time Sensitive Notifications on `com.feernandooff.kinuvo`, and an App ID for `com.feernandooff.kinuvo.notifications`; automatic signing creates these and the Keychain group. `aps-environment` is `development`; archives for TestFlight or the App Store are signed with `production`.

Check the decryption against RFC 8291's test vector on the Mac:

```sh
mise run exec -- apps/ios/tests/test-push-keys.sh
```

To verify HTTPS and the secure WebSocket handshake against an existing Server without pairing a Device:

```sh
SIMCTL_CHILD_KINUVO_TEST_TLS_ORIGIN=https://<machine>.<tailnet>.ts.net \
  ./kotlin test -m sdk -p iosSimulatorArm64
```

This opt-in integration test uses normal certificate verification and does not send credentials. Without `KINUVO_TEST_TLS_ORIGIN` in the test process, it performs no network requests.

## SwiftUI integration proof

The default launch still opens the complete Compose app. Add `--native-shell` to the Xcode scheme's **Arguments Passed On Launch** to run the integration proof, or launch the installed simulator build:

```sh
xcrun simctl launch <simulator-id> com.feernandooff.kinuvo --native-shell
```

SwiftUI owns the `NavigationStack` and native back navigation. **Board** renders in SwiftUI using `BoardDestination.viewModel` (the same `BoardViewModel` and `BoardRepository` used by Compose): load/refresh, create Tasks, change status, show Done, and open a Task's Session. **Knowledge** and **Session** each host one selected Compose screen; their callbacks push native destinations. Setup and pairing reuse the existing Compose flow. Server selection shares `AppViewModel`; switching clears native destinations. App links open native Sessions, pairing links use the shared pairing flow, and OAuth callbacks retain the shared browser handling.

**Full app** opens the complete Compose UI with the same AppViewModel and Server lifetime. Settings and Artifact callbacks use it with the requested initial route. This keeps all existing features accessible without claiming the proof is a complete native replacement. Dismiss it with **Done**. Native Board is recreated on return, refreshing from the shared repository. UIKit hosts fit the native safe area; SwiftUI owns the keyboard and navigation bars.

Import `KotlinModules` only. The `apps/ios` module combines `client/ui` with the on-device Server and holds the Server host: the Server start (`IosEmbedded.kt`), `AppleLanguageModel.swift`, `AppleKeychain.swift` and `AppleBackgroundRuns.swift`. `KinuvoApp` installs the client bridges, then calls `IosEmbeddedKt.startKinuvoWithEmbeddedServer` with the model, Keychain, folder access and background-runs bridges; the Server reopens picked folders through that folder access when it starts. `client/app/src/<feature>` co-locates Repositories, ViewModels and immutable state; `client/ui/src/<feature>` holds Compose rendering. Transport and wire types remain in `client/sdk` and `protocol`. The umbrella exports typed `AppState`, `BoardState`, ViewModels and `Route` data. Flow is a Kotlin interface in the generated Objective-C header, not a Swift `AsyncSequence`: use `NativeApp.observe` / `BoardDestination.observe` for typed main-thread callbacks. Retain the returned `StateObservation` and cancel it on disposal. `BoardDestination.close()` cancels observations and clears its ViewModelStore; `NativeScreenHost.close()` clears hosted destination ViewModels. The Swift representable calls it from `dismantleUIViewController`. Keep `NativeApp` alive for the native shell and close it when that shell ends.

For another native feature, register its shared ViewModel with `DestinationLifetime.own`, observe its `state` with `DestinationLifetime.observe`, and cast the callback value to that feature’s state type in Swift. Objective-C export hides value classes, so Agent, Session, Run, Tool call and Interaction IDs read from Kotlin state arrive as `Any?` in Swift; read and build them as strings through `NativeIds` (`client/ui/src@ios/NativeIds.kt`) and the `KotlinIds.swift` extensions. Generic Flow callbacks erase their Kotlin type in this Objective-C export; the App and Board convenience callbacks are explicitly typed. Close the lifetime when the native destination ends. The native shell owns client activity even while its full-app sheet is shown; that sheet disables Compose’s app-level activity hook.

### Server voice native regression checks

Server transcription uses microphone permission only. Switching between Device and
Server voice releases the previous audio engine's I/O graph. Recording validates
both the hardware and tap formats before installing a tap; an interrupted or reset
recording reports an error and must be started again. Audio callbacks reach shared
state on the main queue and are ignored after recording stops.

Run the production PCM helper against native AVFoundation buffers and converters
on the Mac without booting a simulator or opening the microphone:

```sh
mise run exec -- python3 apps/ios/tests/test-server-audio.py
```

These checks cover unavailable microphone formats, nonfinite and out-of-range
samples, empty buffers, and conversion from common iPhone/Bluetooth sample rates.
The same harness exercises the production recording lifecycle with a substituted
microphone engine: ten start/cancel/restart cycles, late audio from cancelled
recordings, repeated stop/finish, interruption and audio-service reset. These
checks do not reproduce hardware routing or prove an iPhone crash is resolved.
For that, retain the iPhone's `.ips` crash report and verify recording, reply
playback, repeated starts/stops, switching Device/Server voice, and headset or
interruption recovery on a designated device.
