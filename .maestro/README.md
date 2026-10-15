# Mobile E2E

Requires the `feat/websockets-kotlin-toolchain` Mockstro branch, Java 17+, Maestro,
Android SDK tools and Xcode for iOS. Build Mockstro from its checkout (keep this dependency on its feature branch for now):

```sh
cd <mockstro-checkout>
git branch --show-current # must be feat/websockets-kotlin-toolchain
go build -o /private/tmp/mockstro ./cmd/mockstro
```

From the Kinuvo repository root, select a test device explicitly:

```sh
/private/tmp/mockstro run .maestro/flows/mobile/chat.yaml --device <emulator-serial>
IOS_SIMULATOR_NAMES='<simulator-name>' /private/tmp/mockstro --platform ios run .maestro/flows/mobile/chat.yaml --device <simulator-udid>
```

Every flow uses `appId: ${APP_ID}` and carries both `android` and `ios`
tags. Mockstro injects `APP_ID` from the selected platform configuration. Keep
separate flows when the platform has different UI steps or behavior.

Run all eleven shared flows on iOS (smoke and regression) with:

```sh
IOS_SIMULATOR_NAMES='<simulator-name>' /private/tmp/mockstro --platform ios run --tags smoke,regression --device <simulator-udid>
```

Add `--skip-build` when the current debug build is already installed. These flows
exercise the default Compose shell on Android and iOS:

- `chat.yaml`: pairing, Session creation, message sending and the pushed reply.
- `pairing-rejected.yaml`: an expired pairing code stays on the connection screen.
- `board.yaml`: load Tasks, create one, receive a live Task update, and show Done.
- `knowledge.yaml`: empty search, matching search, Note details, linked Notes and back navigation.
- `approval-streaming.yaml`: streamed text, a tool Approval, Allow once, and the completed reply.
- `tool-success.yaml`: a tool call, its result, and the completed reply.
- `tool-error.yaml`: a failed tool result and the Agent's error reply.
- `approval-deny.yaml`: deny with a note and verify that exact decision reaches the Server.
- `session-details.yaml`: favorite and rename the open Session from its "…" menu, then find it under Favorites.
- `computers.yaml`: Live view of a Computer: a pushed frame, Take over, a key from the controls and Hand back.
- `session-return.yaml`: receive an event on Board, return to the Session, then background/resume without losing its transcript or resending the message.

All flows under `flows/mobile/` run unchanged on Android and iOS. Run the shared
suite on Android with:

```sh
/private/tmp/mockstro run --tags smoke,regression --device <emulator-serial>
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

Alternatively, designate an Android AVD with `MAESTRO_AGENT_AVDS=<avd-name>` and use
`--boot-emulator=<avd-name> --headless`. Mockstro builds with `./kotlin`, snapshots
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
