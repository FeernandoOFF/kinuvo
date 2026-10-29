# Kinuvo

Self-hosted personal AI assistant. Terms: `CONTEXT.md`. Decisions: `docs/adr/`.

## Layout

- `harness/`: runs Agents: `core` (KMP), `plugins/` (anytype, exec, executor, firecrawl, mcp, ntfy, protonpass, reminders), `providers/` (apple, codex), `server/` (`runtime` KMP, `jvm` host, `embedded` on-device).
- `shared/`: KMP, used by clients: `protocol`, `client`, `app` (Compose-free feature logic), `ui` (Compose Multiplatform, matching feature packages).
- `apps/`: platform shells (desktop, android, ios, web) and `cli`, the `kinuvo` command (headless server + management commands).

## Build

Kotlin Toolchain (not Gradle): `./kotlin build`, `./kotlin test -m <module> -p jvm`. UI screenshots render to `shared/ui/build/screens/` via `./kotlin test -m ui -p jvm`. Releases: [docs/releasing.md](docs/releasing.md).

## Device workflow

- Use T3 `device_list` and `device_open` only to discover, boot and attach a device to the sidebar for the user to watch. Use the returned device ID for every subsequent operation. Do not use T3 device screenshot tools for inspection or verification.
- For regular device use and exploratory debugging, use the `agent-device` CLI returned by `device_open`, keeping its exact executable path and all target, config and session flags. Read its installed help, prefer current element refs/selectors, and use its screenshots when visual evidence is needed. Verify the requested result in the UI. Routine interaction does not require a regression run.
- For repeatable app verification after code changes, read [.maestro/README.md](.maestro/README.md) and run the shared flows through `./mockstrow --dev`. Open the designated device through T3 first so the user can watch, then pass its serial/UDID to Mockstro with `--device`. Use only a device designated for this checkout. If T3 is unavailable, report that limitation; Mockstro may then select/boot a designated device itself.
- Mockstro owns test builds/installs, ownership, network overrides, Maestro execution and cleanup. Use its mocked remote Server and `--tags smoke,regression`; select the platform explicitly with `--platform android` or `--platform ios`. Use `--skip-build` only when the installed debug build matches the code being tested.
- Finish interactive automation before handing a device to Mockstro. Do not run `agent-device`, standalone Maestro/Maestro MCP, or manual ADB UI commands against a device while Mockstro owns a run. Do not treat the T3 sidebar's passive video stream as an interactive controller.
- Maestro MCP is optional for explicitly requested Maestro investigation or flow authoring; it is not the default interactive driver and must not bypass Mockstro for repository E2E tests. If a required tool is unavailable, report that and use an available alternative without silently changing the test setup.
- Use ADB on Android and `xcrun simctl`/`devicectl` on Apple platforms for lower-level tasks the interactive driver does not cover, such as logs, shell commands, port forwarding and platform diagnostics. Always target an explicit serial/UDID; do not use raw coordinate input as the default UI driver.
- Extend `.maestro/flows/mobile/` and its fixtures for new regression coverage. Use a real Server only when the task explicitly requires that integration. Keep the checkout's home and device assignments separate from other branches.

## Preferences

- Use the `CONTEXT.md` terms in code and docs.
- Document current behavior and decisions, with future work clearly identified. Omit POC narratives, abandoned implementations, stash references and development-session history.
- Use imports instead of fully qualified type names in code, except where names conflict.
- Keep apps thin: platform code goes in `expect`/`actual` + Koin modules in `shared/ui`. Don't pass platform dependencies through composables.
- The embedded server is just another `Server`. No special cases in ViewModels; UI labels only.
- ViewModels expose one state data class with orthogonal fields, using explicit backing fields (`val state: StateFlow<S>` / `field = MutableStateFlow(...)`), not `_state` pairs.
- Write minimal code with few comments, and match the surrounding style.

## Shared client logic

- Put feature behavior, validation, Repositories, ViewModels and immutable state in `shared/app`. Compose and SwiftUI call the same shared ViewModels; move reusable behavior out of screens before adding another UI. Keep `shared/app` free of Compose, UIKit and SwiftUI dependencies.
- Co-locate logic by feature in `shared/app/src/<feature>` and mirror those feature names in `shared/ui` and native UI folders. Views own rendering, input bindings and navigation; transport stays in `shared/client` and wire types in `shared/protocol`.
- Reuse `ConnectedServer` for connection work and caches. Native destinations own ViewModel lifetimes and cancel observations on disposal; selected Compose screens share the same app and Server lifetime and hand navigation back to their native host.
- Before adding a SwiftUI feature, observing Kotlin state or hosting a Compose screen, read [the iOS integration guide](apps/ios/README.md#swiftui-integration-proof). Follow its observation and disposal pattern. Test shared behavior in `shared/app`, Compose rendering in `shared/ui`, and native navigation/lifecycle in the iOS integration.

## Isolated local testing

- Read [docs/local-testing.md](docs/local-testing.md) before running a local Server or app. If present, `docs/local-testing.local.md` records this machine's assignments.
- Use `mise run desktop:personal` for the personal home and `mise run desktop:test` for disposable testing. Defaults are `~/.kinuvo` (8787) and `~/.kinuvo-test` (8791); override with `KINUVO_PERSONAL_HOME`/`KINUVO_PERSONAL_PORT` or `KINUVO_TEST_HOME`/`KINUVO_TEST_PORT`. Never copy data between homes without a user request.
- Other tasks inherit `KINUVO_HOME` and `KINUVO_EMBEDDED_PORT` normally; no checkout initialization or pins are required. Use scoped `mise run kotlin -- ...` commands and `mise run exec -- ...` for scripts. Do not change OS `HOME`.
- The vendor-managed `./kotlin` wrapper remains unchanged. Packaging and Mockstro inherit the caller's environment without loading checkout pins.
- Before starting a Server, stop any Server using the same home. The macOS launch service has one label across homes; manage it only from its assigned checkout.
- Fix shared bugs on main, then bring main into experimental branches. Keep experimental data and app identities separate.
