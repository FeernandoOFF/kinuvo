# Kinuvo

Self-hosted personal AI assistant. Terms: `CONTEXT.md`. Decisions: `docs/adr/`.

## Layout

- `protocol/`: wire and domain values (KMP, incl. web).
- `client/`: `sdk` (transport), `app` (Compose-free feature logic), `ui` (Compose Multiplatform, matching feature packages; `android-computer`).
- `harness/`: runs Agents, no Server dependencies: `api` (execution contracts), `builtin` (the built-in harness, KMP), `acp` (external ACP harnesses).
- `gateways/`: clients of a Server that expose it elsewhere: `acp-agent` (serves an Agent as an ACP agent over stdio, `kinuvo.gateways.acp`).
- `server/`: `plugin-api` (Server plugin SPI, `kinuvo.server.plugin`, Koog-free; plugins depend on it, never on `harness/builtin`), `core` (KMP implementations), `jvm` host, `embedded` on-device host with each phone's plugin list.
- `models/`: verified model downloads. `providers/` (apple, codex, koog, litert) and `plugins/` (anytype, bitwarden, browser, caldav, exec, executor, firecrawl, mcp, ntfy, onepassword, protonpass, reminders, todoist, voice; `cua` is the desktop Computer driver).
- `testing/`: `harness-testing` (scripted models), `server-testing` (real in-process Server fixtures, module-edge test), `fixture-server` (Maestro launcher).
- `apps/`: platform shells (desktop, android, ios, web) and `cli`, the `kinuvo` command (headless server + management commands).

## Build

Kotlin Toolchain (not Gradle): `./kotlin build`, `./kotlin test -m <module> -p jvm`. UI screenshots render to `client/ui/build/screens/` via `./kotlin test -m ui -p jvm`. Releases: [docs/releasing.md](docs/releasing.md).

## Commits

- Every commit follows [Conventional Commits](https://www.conventionalcommits.org): `type(scope): subject`. CI rejects pull requests with other commit subjects (merges excepted).
- Types: `feat`, `fix`, `perf`, `refactor`, `style`, `test`, `docs`, `build`, `ci`, `chore`, `revert`. Append `!` for breaking protocol or config changes.
- Scope is the feature or area, lowercase, matching `client/app` feature names where one fits: `agents`, `sessions`, `chat`, `voice`, `browser`, `screens`, `computers`, `surfaces`, `notifications`, `models`, `approvals`, `onboarding`, `settings`, `ui`, `server`, `core`, `plugins`, `ios`, `android`, `desktop`, `cli`, `release`. Omit it for repo-wide changes.
- Subject: imperative, lowercase start, no trailing period, at most 72 characters. Explain why in the body when it isn't obvious.
- One logical change per commit; never `wip`, `fixup` or round-numbered subjects on main.

## Device workflow

- Use T3 `device_list` and `device_open` only to discover, boot and attach a device to the sidebar for the user to watch. Use the returned device ID for every subsequent operation. Do not use T3 device screenshot tools for inspection or verification.
- For regular device use and exploratory debugging, use the `agent-device` CLI returned by `device_open`, keeping its exact executable path and all target, config and session flags. Read its installed help, prefer current element refs/selectors, and use its screenshots when visual evidence is needed. Verify the requested result in the UI. Routine interaction does not require a regression run.
- For repeatable app verification after code changes, run the Compose UI flows first: `./kotlin test -m ui -p jvm --include-classes='kinuvo.ui.e2e.*'` (screenshots in `client/ui/build/screens/flows/`). They run the whole app against the real in-process Server (`TestServer`, module `server-testing`); WireMock and `MockServer` are removed. For device-level behavior, read [.maestro/README.md](.maestro/README.md): Mockstro is WireMock-based, so `mockstrow` regression runs are paused until it is adapted. Run the flows in `.maestro/flows/mobile/` with plain Maestro against the fixture launcher (`./kotlin run -m fixture-server -- chat`, which prints its URL and pairing code). Open the designated device through T3 first so the user can watch, and use only a device designated for this checkout. If T3 is unavailable, report that limitation.
- Finish interactive automation before running Maestro on a device. Do not run `agent-device`, Maestro MCP or manual ADB UI commands against a device while a Maestro run owns it. Do not treat the T3 sidebar's passive video stream as an interactive controller.
- Maestro MCP is optional for explicitly requested Maestro investigation or flow authoring; it is not the default interactive driver. If a required tool is unavailable, report that and use an available alternative without silently changing the test setup.
- Use ADB on Android and `xcrun simctl`/`devicectl` on Apple platforms for lower-level tasks the interactive driver does not cover, such as logs, shell commands, port forwarding and platform diagnostics. Always target an explicit serial/UDID; do not use raw coordinate input as the default UI driver.
- Extend `client/ui/test@jvm/e2e/` and `server-testing` (Scenario seeding, fakes, request spy) for new regression coverage; extend `.maestro/flows/mobile/` only for device-level behavior (native pickers, permissions, voice, OS lifecycle, real network). Use a real Server only when the task explicitly requires that integration. Keep the checkout's home and device assignments separate from other branches.

## Preferences

- Use the `CONTEXT.md` terms in code and docs.
- Document current behavior and decisions, with future work clearly identified. Omit POC narratives, abandoned implementations, stash references and development-session history.
- Use imports instead of fully qualified type names in code, except where names conflict.
- Keep apps thin: platform code goes in `expect`/`actual` + Koin modules in `client/ui`. Don't pass platform dependencies through composables. The shared Koin graph (`appModule`) lives in `client/app/src/AppModule.kt`; `client/ui` adds `platformModule`.
- The embedded server is just another `Server`. No special cases in ViewModels; UI labels only.
- ViewModels expose one state data class with orthogonal fields, using explicit backing fields (`val state: StateFlow<S>` / `field = MutableStateFlow(...)`), not `_state` pairs.
- Write minimal code with few comments, and match the surrounding style.

### Docs

- Write `docs/` user guides in plain language for owners; omit implementation internals.
- Record decisions in `docs/adr/`: short, one file per decision, next free number, frontmatter `title` and `description`.
- Put contributor how-tos under the Contributing sidebar group or in module READMEs.
- Do not commit plan, handoff or session documents.

## Shared client logic

- Put feature behavior, validation, Repositories, ViewModels and immutable state in `client/app`. Compose and SwiftUI call the same shared ViewModels; move reusable behavior out of screens before adding another UI. Keep `client/app` free of Compose, UIKit and SwiftUI dependencies.
- Co-locate logic by feature in `client/app/src/<feature>` and mirror those feature names in `client/ui` and native UI folders. Views own rendering, input bindings and navigation; transport stays in `client/sdk` and wire types in `protocol`.
- Reuse `ConnectedServer` for connection work and caches. Native destinations own ViewModel lifetimes and cancel observations on disposal; selected Compose screens share the same app and Server lifetime and hand navigation back to their native host.
- Before adding a SwiftUI feature, observing Kotlin state or hosting a Compose screen, read [the iOS integration guide](apps/ios/README.md#swiftui-integration-proof). Follow its observation and disposal pattern. Test shared behavior in `client/app`, Compose rendering in `client/ui`, and native navigation/lifecycle in the iOS integration.

## Isolated local testing

- Read [docs/local-testing.md](docs/local-testing.md) before running a local Server or app. If present, `docs/local-testing.local.md` records this machine's assignments.
- Use `mise run desktop:personal` for the personal home and `mise run desktop:test` for disposable testing. Defaults are `~/.kinuvo` (8787) and `~/.kinuvo-test` (8791); override with `KINUVO_PERSONAL_HOME`/`KINUVO_PERSONAL_PORT` or `KINUVO_TEST_HOME`/`KINUVO_TEST_PORT`. Never copy data between homes without a user request.
- Other tasks inherit `KINUVO_HOME` and `KINUVO_EMBEDDED_PORT` normally; no checkout initialization or pins are required. Use scoped `mise run kotlin -- ...` commands and `mise run exec -- ...` for scripts. Do not change OS `HOME`.
- The vendor-managed `./kotlin` wrapper remains unchanged. Packaging and Mockstro inherit the caller's environment without loading checkout pins.
- Before starting a Server, stop any Server using the same home. The macOS launch service has one label across homes; manage it only from its assigned checkout.
- Fix shared bugs on main, then bring main into experimental branches. Keep experimental data and app identities separate.
