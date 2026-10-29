# Mobile E2E

Use the repository wrapper with the current development CLI. The latest published
release (`v0.1.0-rc.1`) predates the YAML config and Kotlin Toolchain/WebSocket support
used by this suite. Install the development binary once from the Mockstro checkout:

```sh
make install-dev
```

Requires Java 17+ and Android SDK tools or Xcode for the selected platform.
`setup` validates fixtures and installs missing Maestro and WireMock dependencies.

From the Kinuvo repository root:

```sh
./mockstrow --dev setup
./mockstrow --dev --owner agent status --json
./mockstrow --dev --owner agent --platform android run --headless .maestro/flows/mobile/chat.yaml
./mockstrow --dev --owner agent --platform ios run --headless .maestro/flows/mobile/chat.yaml
```

`.maestro/mockstro.yaml` configures both platform app IDs, full integration, Kotlin
Toolchain builds, and the pinned WireMock beta with `/ws`. Put machine-specific device
names in the ignored `.maestro/mockstro.local.yaml`:

```yaml
platforms:
  android:
    devices: [Your_Android_AVD]
  ios:
    devices: [Your iPhone simulator]
```

Agents use designated devices; Mockstro can select and boot a free one. Add
`--device <serial-or-udid>` to select a particular designated device.
`MOCKSTRO_DEV_CLI=/path/to/mockstro-dev` selects a development binary outside `PATH`.
Omit `--platform` to run both configured platforms concurrently.
When a compatible release is published, add its `cliVersion` to the shared YAML and
use `./mockstrow` without `--dev`.

Every flow uses `appId: ${APP_ID}` and carries both `android` and `ios`
tags. Mockstro injects `APP_ID` from the selected platform configuration. Keep
common behavior in shared flows; use native Maestro platform conditions for
interactions that differ.

Run all shared flows on iOS (smoke and regression) with:

```sh
./mockstrow --dev --owner agent --platform ios run --headless --tags smoke,regression
```

Add `--skip-build` when the current debug build is already installed. These flows
exercise the default Compose shell on Android and iOS:

- `settings-journey.yaml`: one connection and chat across Home, Agent settings, nested Back and scroll restoration, Keep editing/Discard, Skill edit/discard/save, Workspace, Routine draft guards, Tools, Model providers, Harnesses, and an Agent rename. Server request checks ensure only the intended saves are sent.
- `chat.yaml`: pairing, Session creation, message sending and the pushed reply.
- `attachment-picker.yaml`: Files remains enabled for a model with text and image modalities and opens the native file picker.
- `pairing-rejected.yaml`: an expired pairing code stays on the connection screen.
- `board.yaml`: open Board from Home in the drawer, load Tasks, create one, receive a live Task update, and show Done.
- `knowledge.yaml`: open Knowledge from Home in the drawer, empty search, matching search, Note details, linked Notes and back navigation.
- `approval-streaming.yaml`: streamed text, a tool Approval, Allow once, and the completed reply.
- `tool-success.yaml`: a tool call, its result, and the completed reply.
- `tool-error.yaml`: a failed tool result and the Agent's error reply.
- `approval-deny.yaml`: deny with a note and verify that exact decision reaches the Server.
- `session-details.yaml`: favorite and rename the open Session from its "…" menu, then find it under Favorites.
- `computers.yaml`: open Computers from Home in the drawer, Live view of a Computer: a pushed frame, Take over, a key from the controls and Hand back.
- `session-return.yaml`: open Board from Home, receive an event on Board, return to the Session, then background/resume without losing its transcript or resending the message.

On phones the drawer is an owner rail (Home, Agents, New agent, Server, Settings) beside a column: the
Agent's Sessions, or with Home selected Today, Board and the Server-wide Computers, Knowledge and Artifacts.
Flows reach those screens with `tapOn: "Agents and sessions"`, `tapOn: "Home"`, then the destination.
`mobile-inspector` serves `agent.get` from `mobile/agent-details` and `agent.update` from `mobile/agent-update`.

All flows under `flows/mobile/` run unchanged on Android and iOS. Run the shared
suite on Android with:

```sh
./mockstrow --dev --owner agent --platform android run --headless --tags smoke,regression
```

Tool responses and Agent events are mocked at the WebSocket boundary. These tests
verify the client's rendering, decisions and lifecycle; actual tool execution is
covered by the harness's own tests. Background/resume uses Maestro's
[`launchApp: stopApp: false`](https://docs.maestro.dev/reference/commands-available/launchapp)
to retain the running process.

Each flow asserts the relevant received WebSocket requests as well as the UI.
Server pushes happen after UI readiness assertions, without fixed sleeps. Mockstro
restarts the WebSocket server and stops the app between scenarios; each launch
uses fresh test preferences and credentials.

Use `--boot-emulator <designated-device-name>` to request a specific stopped device.
Mockstro builds with `./kotlin`, snapshots
and installs the artifact, loads the pinned WireMock 4 beta, writes the runtime
override, and cleans up its override and forwarding when the run finishes.

The shared mobile smoke flow pairs with a remote Server, opens the mocked Agent, creates a Session,
sends a message, and asserts the pushed reply. Network assertions verify `connect`,
Agent loading, Session creation and `chat.send`. Fixtures use the real JSON
protocol and client; feature behavior is shared with ordinary app launches.
The Agent is selected automatically because this fixture offers one Agent.

Android accepts schema 1 from the base64 Settings.Global key `kinuvo_e2e_override`
only when the app is debuggable. Mockstro grants the development permission
`WRITE_SECURE_SETTINGS`, which lets the app publish `kinuvo_e2e_accepted_run_id`.
iOS accepts `e2e-config.json` in the installed bundle only in a debug simulator
build. The Swift Debug target must define `DEBUG`. It publishes acceptance to
`Documents/e2e-accepted-run-id`. Overrides require an unexpired run ID and a
`ws://127.0.0.1:<port>/...` endpoint.

Accepted test launches use in-memory preferences and Device credentials, skip
owner onboarding, and offer ordinary remote Servers. Pairing starts fresh on
each process launch. Ordinary preferences, Keychain/Keystore credentials and
the embedded Server's data are kept separate. Pairing persistence across process
restarts is therefore outside these smoke flows.

Add feature requests as native message fixtures in `wiremock/stubs/ws/` and
reference them from profiles. Server events use `type`, `event`, `payload` and
`seq`; response IDs echo `{{jsonPath message.body '$.id'}}`. The explicit HTTP
upgrade fixture is required by the pinned beta. Generated runs and JARs are ignored.

## Visible devices and interactive use

Use T3 to discover and open a designated device before device work so the user can
watch its live view in the sidebar. T3 is the boot/view entry point; interactive inspection, screenshots
and UI actions use the `agent-device` executable and exact target/config/session
flags returned by `device_open`. Read that installed CLI's help for its supported
commands and keep element references current. Use ADB or Apple platform tools for
low-level diagnostics, always with an explicit device target.

For E2E tests, finish the interactive session and hand the same booted device to
Mockstro. Obtain the current serial/UDID from T3 rather than assuming it is stable:

```sh
./mockstrow --dev --owner agent --platform android run --device <android-serial> --tags smoke,regression
./mockstrow --dev --owner agent --platform ios run --device <simulator-udid> --tags smoke,regression
```

The device must be designated in this checkout's local Mockstro configuration.
Mockstro keeps responsibility for test build/install, ownership, overrides and
cleanup. Do not send interactive commands through another driver during its run.
The sidebar video may stay visible; it does not replace Mockstro's test ownership.
If T3 is unavailable, report that limitation; Mockstro can select and boot a
designated device itself, without promising a sidebar view.

Regular user-requested device interaction does not require running the full suite.
Maestro MCP is an optional aid for explicitly requested Maestro investigation or
flow authoring; execute repository E2E flows through Mockstro.
