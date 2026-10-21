# Kinuvo for iOS

Thin SwiftUI shell for the shared Compose UI, with an opt-in native navigation integration. Runs a foreground Server on-device or connects to a remote Server. The app requires iOS 18.5 or later; on-device inference requires iOS 26+ and an Apple Intelligence-capable device.

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

## On-device demo

The embedded Server is selected on a fresh install. If this app already remembers a remote Server, select **Embedded** in the Server menu. No pairing code, cloud login or API key is needed. The model picker shows **Apple on-device model**, and **Apple Private Cloud Compute** when the build includes it (below).

Use a physical Apple Intelligence-capable device (iPhone 15 Pro/Pro Max or a supported newer iPhone, or a supported iPad). Enable Apple Intelligence in Settings and wait for its model download to finish. The simulator can verify the UI and unavailable-model messages; it does not prove local inference works.

1. Install and launch on the device using Xcode or the device command above.
2. Enable airplane mode and keep Kinuvo in the foreground.
3. Send “Remember that I prefer tea to coffee.” Check that the memory tool succeeds before the Agent says it saved the preference.
4. Start a new Session and ask “What drink do I prefer?”
5. Relaunch the app and repeat the question in another new Session to verify persistence.

The same core `AgentRun`, file repositories and Approval policy run on-device. Apple's Foundation Models stream replies and generate structured Memory requests; the harness executes the requests and supplies their results on the next model turn. Agents, Sessions, Memory, User Notes and Trust rules live in the app's Application Support/Kinuvo directory. Starting a new Session preserves the Agent's Memory and User Notes.

Apple's model can use up to six tools per turn, Memory and web search first (1,200 characters each for Memory and User Notes). It compacts longer conversations, though its small context window may still require a new Session. For full tool use, pick ChatGPT / Codex in the model picker after signing in under Settings → Model providers; the sign-in is kept in the iOS Keychain. Remote MCP servers, Firecrawl web search, ntfy notifications, Scripts, Knowledge (Markdown or Anytype), Tasks and this Device's tools work on-device. Attachments, Skills, Routines, local (stdio) MCP and shell tools require a remote Server. A run you start keeps going after you leave the app, shown as a Live Activity you can cancel (iOS 26 continued processing), or for iOS's short grace period when it refuses one; runs waiting for an Approval or an answer stop. This Server does not accept other Devices or run scheduled work.

On unsupported devices or before the model is ready, sending a message shows the reason and setup guidance. You can still connect to a remote Server.

### Apple Private Cloud Compute

On iOS 27, Apple's larger server model (32K context, same privacy guarantees, a daily limit per iCloud account) can run Agents too. It shows as **Apple Private Cloud Compute** in the model picker once the build includes it. The on-device model's context window is read from the device (4,096 or 8,192 tokens).

1. Apply for Private Cloud Compute access at developer.apple.com/private-cloud-compute.
2. Once granted, add `com.apple.developer.private-cloud-compute` (`true`) to `apps/ios/Kinuvo.entitlements`.
3. Add `KINUVO_PCC` to the target's Active Compilation Conditions (`SWIFT_ACTIVE_COMPILATION_CONDITIONS`).
4. Build with Xcode 27 (iOS 27 SDK), e.g. `DEVELOPER_DIR=/Applications/Xcode-27.0.0.app/Contents/Developer ./kotlin run -m ios -p iosArm64 --device-id <id>`.

Without the entitlement, Private Cloud Compute reports itself available but stops the app on its first request, so the model stays out of builds that don't set `KINUVO_PCC`.

## Voice

Dictation (the microphone in the composer) and voice chat (the button beside it when the composer is empty) run speech recognition and speech on-device, asking for microphone and Speech Recognition access the first time. Replies come from the Session's model as usual. Voice chat keeps the microphone open while it speaks, with Apple's echo cancellation, so you can talk over a reply to cut it short; Pause stops it and the microphone until Resume. Premium and enhanced voices downloaded in Settings → Accessibility → Spoken Content are used when available. The simulator has no echo cancellation; use a device.

## Remote Server

In the desktop app, open the Server menu and choose **Pair a phone**. For a headless Server, run `kinuvo pair` on that machine (`kinuvo pair --manage` also grants Server configuration and Approvals). Open the iOS link or enter the advertised address and pairing code in the iOS app. Codes expire after ten minutes and work once.

With `--tailscale`, use the advertised `https://<machine>.<tailnet>.ts.net` address and connect the iPhone to the same tailnet. HTTPS and secure WebSockets use Apple's networking engine. See [browser access](../../docs/browser-access.md) for the Server setup. For direct private-network addresses, use the same Wi-Fi or VPN and allow local-network access when iOS asks. `localhost` on an iPhone refers to the phone, so use the Server machine's address. The simulator can reach a Server on the Mac through `127.0.0.1`.

Pairing is remembered across app launches. The shared client reconnects while the app is active; remote runs remain on the Server in the background. iOS push notifications are not included.

To verify HTTPS and the secure WebSocket handshake against an existing Server without pairing a Device:

```sh
SIMCTL_CHILD_KINUVO_TEST_TLS_ORIGIN=https://<machine>.<tailnet>.ts.net \
  ./kotlin test -m client -p iosSimulatorArm64
```

This opt-in integration test uses normal certificate verification and does not send credentials. Without `KINUVO_TEST_TLS_ORIGIN` in the test process, it performs no network requests.

## SwiftUI integration proof

The default launch still opens the complete Compose app. Add `--native-shell` to the Xcode scheme's **Arguments Passed On Launch** to run the integration proof, or launch the installed simulator build:

```sh
xcrun simctl launch <simulator-id> dev.kinuvo.ios --native-shell
```

SwiftUI owns the `NavigationStack` and native back navigation. **Board** renders in SwiftUI using `BoardDestination.viewModel` (the same `BoardViewModel` and `BoardRepository` used by Compose): load/refresh, create Tasks, change status, show Done, and open a Task's Session. **Knowledge** and **Session** each host one selected Compose screen; their callbacks push native destinations. Setup and pairing reuse the existing Compose flow. Server selection shares `AppViewModel`; switching clears native destinations. App links open native Sessions, pairing links use the shared pairing flow, and OAuth callbacks retain the shared browser handling.

**Full app** opens the complete Compose UI with the same AppViewModel and Server lifetime. Settings and Artifact callbacks use it with the requested initial route. This keeps all existing features accessible without claiming the proof is a complete native replacement. Dismiss it with **Done**. Native Board is recreated on return, refreshing from the shared repository. UIKit hosts fit the native safe area; SwiftUI owns the keyboard and navigation bars.

Import `KotlinModules` only. `shared/app/src/<feature>` co-locates Repositories, ViewModels and immutable state; `shared/ui/src/<feature>` holds Compose rendering. Transport and wire types remain in `shared/client` and `shared/protocol`. The umbrella exports typed `AppState`, `BoardState`, ViewModels and `Route` data. Flow is a Kotlin interface in the generated Objective-C header, not a Swift `AsyncSequence`: use `NativeApp.observe` / `BoardDestination.observe` for typed main-thread callbacks. Retain the returned `StateObservation` and cancel it on disposal. `BoardDestination.close()` cancels observations and clears its ViewModelStore; `NativeScreenHost.close()` clears hosted destination ViewModels. The Swift representable calls it from `dismantleUIViewController`. Keep `NativeApp` alive for the native shell and close it when that shell ends.

For another native feature, register its shared ViewModel with `DestinationLifetime.own`, observe its `state` with `DestinationLifetime.observe`, and cast the callback value to that feature’s state type in Swift. Generic Flow callbacks erase their Kotlin type in this Objective-C export; the App and Board convenience callbacks are explicitly typed. Close the lifetime when the native destination ends. The native shell owns client activity even while its full-app sheet is shown; that sheet disables Compose’s app-level activity hook.
