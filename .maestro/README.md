# Mobile E2E

Screen and flow behavior is covered by the Compose UI flows in `shared/ui/test@jvm/e2e/`,
which run the whole app in-process on JVM against the real Server runtime
(`TestServer`, module `harness/server/server-testing`) with seeded Sessions, a scripted
model and fakes for tools, Browser, Computers and external harnesses:

```sh
./kotlin test -m ui -p jvm --include-classes='kinuvo.ui.e2e.*'
```

Their screenshots go to `shared/ui/build/screens/flows/`. Maestro keeps only the flows
that need a real device: the real WebSocket, native pickers, microphone permission and
voice, and OS-level background/resume. Add new screen or flow coverage to
`shared/ui/test@jvm/e2e/` and `server-testing`; add a Maestro flow only for device-level
behavior.

## Running flows

Mockstro and its WireMock profiles are gone from this suite, and `.maestro/mockstro.yaml`
is left as it was. Mockstro is WireMock-based, so `mockstrow` regression runs are paused
until Mockstro is adapted to the fixture launcher. Run flows with plain Maestro instead.

Start the fixture Server, a real WebSocket Server on `127.0.0.1:18081` with seeded data and a
scripted model (a test-only module, not part of the `kinuvo` CLI):

```sh
./kotlin run -m fixture-server -- chat
./kotlin run -m fixture-server -- showcase --port=18082
```

It prints the URL and the pairing code (a new one when the code is used or expires). The
port can also come from `KINUVO_FIXTURE_PORT`. An Android device or emulator reaches it with
`adb -s <serial> reverse tcp:18081 tcp:18081`; an iOS simulator shares the host's loopback.
Install the debug build, then pass the code to Maestro:

```sh
maestro --device <serial-or-udid> test -e APP_ID=com.feernandooff.kinuvo.full -e PAIRING_CODE=<code> .maestro/flows/mobile/chat.yaml
```

These flows have not been run against the launcher yet. Mockstro's launch override used to skip
onboarding and point the app at its address; with plain Maestro a fresh install shows the
ordinary first-run screens, so the pairing steps may need the address typed or onboarding finished first.

Every flow uses `appId: ${APP_ID}` and carries both `android` and `ios` tags. Keep common
behavior in shared flows; use native Maestro platform conditions for interactions that differ.
Scenarios live in `harness/server/fixture-server/src/Main.kt`:

- `chat`: Agent "E2E Agent", the Board task "Plan mobile tests", and the scripted reply "Hello from the fixture Server".
- `showcase`: the dense data of the Compose `showcase` flows.

Flows that run against the launcher:

- `chat.yaml`: pairing, Session creation, message sending and the reply.
- `session-return.yaml`: open Board from Home, return to the Session, then background/resume without losing its transcript or resending the message.
- `review/markdown.yaml` (`showcase`): the Markdown review screenshots.

Paused until the launcher gains matching scenarios: `attachment-picker.yaml` (a model with
text and image modalities, native file picker), `external-*.yaml` (an external harness Session;
a new external Session reports no file types until it is open, so these need an existing
Session first) and `voice.yaml` (voice chat with the microphone permission granted).

## Visible devices and interactive use

Use T3 to discover and open a designated device before device work so the user can
watch its live view in the sidebar. T3 is the boot/view entry point; interactive inspection, screenshots
and UI actions use the `agent-device` executable and exact target/config/session
flags returned by `device_open`. Read that installed CLI's help for its supported
commands and keep element references current. Use ADB or Apple platform tools for
low-level diagnostics, always with an explicit device target.

Finish the interactive session before running Maestro on the same device, and do not send
interactive commands through another driver during its run. The sidebar video may stay
visible; it does not replace Maestro's ownership of the run. Regular user-requested device
interaction does not require running the flows. Maestro MCP is an optional aid for explicitly
requested Maestro investigation or flow authoring.
