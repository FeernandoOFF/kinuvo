# Kinuvo

Self-hosted personal AI assistant. Terms: `CONTEXT.md`. Decisions: `docs/adr/`.

## Layout

- `harness/`: runs Agents: `core` (KMP), `plugins/` (anytype, exec, executor, firecrawl, mcp, ntfy, protonpass, reminders), `providers/` (apple, codex), `server/` (`runtime` KMP, `jvm` host, `embedded` on-device).
- `shared/`: KMP, used by clients: `protocol`, `client`, `app` (Compose-free feature logic), `ui` (Compose Multiplatform, matching feature packages).
- `apps/`: platform shells (desktop, android, ios, web) and `cli`, the `kinuvo` command (headless server + management commands).

## Build

Kotlin Toolchain (not Gradle): `./kotlin build`, `./kotlin test -m <module> -p jvm`. UI screenshots render to `shared/ui/build/screens/` via `./kotlin test -m ui -p jvm`. Releases: [docs/releasing.md](docs/releasing.md).

## Device tests

- Before Android or iOS device verification, read [.maestro/README.md](.maestro/README.md) and run the shared flows through Mockstro.
- Use the repository wrapper with `./mockstrow --dev`; `.maestro/mockstro.yaml` configures both platforms.
- Let Mockstro manage device selection/booting, build/install, ownership, network overrides and cleanup. Use its mocked remote Server and `--tags smoke,regression`; select iOS with `--platform ios`. Use `--skip-build` only when the installed debug build matches the code being tested.
- Extend `.maestro/flows/mobile/` and its fixtures for new feature coverage. Use direct device automation or a real Server only when the task explicitly requires that integration.

## Preferences

- Use the `CONTEXT.md` terms in code and docs.
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
- Each checkout pins an absolute data home and a port in ignored `.kinuvo-home` and `.kinuvo-port`. Use `./dev init` to configure a new checkout. Do not overwrite pins or copy data between homes without a user request.
- In pinned checkouts use `./dev server`, `./dev desktop`, `./dev android`, `./dev ios`, and `./dev cli ...`. Use `./dev kotlin ...` for toolchain commands and `./dev exec ...` for other scripts that may access data. Do not launch installed apps, jars or the global CLI directly; those bypass the checkout's environment. Do not change OS `HOME`.
- The vendor-managed `./kotlin` wrapper remains unchanged. Repository packaging and Mockstro wrappers load any local pins; new data-accessing scripts should source `scripts/project-env.sh` with `kinuvo_project_root` set to their checkout root.
- Before starting a Server, stop any Server using the same home. The launcher's PID check is advisory, not an exclusive lock. The macOS launch service has one label across homes; manage it only from its assigned checkout.
- Fix shared bugs on main, then bring main into experimental branches. Keep experimental data and app identities separate.
