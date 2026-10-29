# Screen use implementation: stage 1

Scope: restore and reconcile Browser only. Unified streaming, Screens control and
UI redesign remain for later stages. User decisions and Approved implementation
direction at the bottom of the design override earlier proposals.

## Commits and reconciliation

- `7b2f643`: authorized revert of removal `5cc083d` (an ancestor of starting
  `0ad0aac`, branch `feat/agent-screens`). The sole conflict was `Api.kt`:
  restored Browser methods/events without obsolete voice declarations.
- `6a3b5c3`: only the exact research, design and mockups planning artifacts.
- Follow-up foundation commit: ordinary Agent Risk/Approval/Trust/Deny policy
  permits password-field input by explicit user scope. No per-site Trust or
  OTP/card rules added. Browser input adds no value logging. Management auth,
  Agent+Session ownership, URL policy, Takeover, sandbox/private pipe and
  per-Agent profiles remain intact.
- Current harness composition, plugin feature/tool registration, Server handlers,
  ConnectedServer repository, route, Koin, chat entry and settings reconciled.
  Restored app ViewModel already uses one state data class and explicit backing field.
- No ProtocolVersion or mismatch implementation existed in this checkout.
  Explicit exact protocol version 2 now gates Connect before authentication;
  missing/future versions are rejected, and browser HTTP auth returns 409 before
  cookies/pairing/rotation. Client checks the version before feature decoding;
  mismatch stops retries without rejecting/deleting saved credentials.
- `docs/controlled-browser.md`: intended field-policy change, compatibility and
  operator-managed Ubuntu 24.04 AppArmor sysctl workaround; sandbox stays on.
  No host sysctl changed.

## Isolation and evidence

All compiler commands were scoped and sequential. No pins existed or were
created. The normal mise wrapper requires both pins even for unit tests, so a
new temporary ignored `mise.local.toml` overrode only the kotlin task to source
`scripts/project-env.sh` and forward arguments to the unchanged vendor wrapper.
It was removed after validation. Tests used
`KINUVO_HOME=/private/tmp/kinuvo-browser-stage1.Zu3FX7`; no OS HOME changes,
installed Server/service/Tailscale changes or other-checkout launches.
Sandboxed first test attempt could not write Kotlin caches; scoped tests then
ran with elevated cache access. No approval rejection or product blocker.

Completed scoped runs (each via `mise run kotlin --`, `-p jvm`):

| Module / filter | Result |
| --- | --- |
| `test -m protocol` | 24/24 passed |
| `test -m browser` | 3/3 passed, including real sandboxed managed Chromium |
| `test -m runtime` | 83/83 passed |
| `test -m jvm` | 100/100 passed, including both BrowserServerTest cases and HTTP 409/pre-pairing check |
| `test -m jvm --include-classes='*ServerSecurityTest'` | final cleanup recheck: 7/7 passed |
| `test -m app --include-classes='*BrowserStateTest'` | 1/1 passed; shared/app compiled |
| `test -m client` | 1/1 passed; mismatch before unknown-feature decoding, credentials preserved |

Browser proof image: `/private/tmp/kinuvo-browser-stage1.Zu3FX7/browser-runtime-proof/browser-proof.jpg`.
The real runtime tests used temporary profiles and localhost fixtures, not a real
account. Packaged-jar integration, Linux/Windows, UI rendering, JS/web compilation
and mobile builds/runtime were not exercised. No device/Mockstro or app launches.
Temporary test data was retained for inspection. Existing expected failure-path
stack traces appeared in passing runtime/Server tests; no test failures.

## Next actions

1. Implement the shared streaming/control protocol through runtime and client,
   with bounded latest-frame delivery separated from response/Approval outbox.
2. Add Browser CDP screencast and honest Computer capture capabilities/fallback.
   Preserve exclusive per-tab Takeover across disconnect until explicit handback.
3. Persist safe tab URL/identity/order/Agent+Session ownership and held recovery;
   never replay inputs. Baseline currently persists sign-ins, not tabs.
4. Replace the restored polling ViewModel/UI with shared/app Screens behavior and
   responsive Live view; validate rendering and scoped mobile builds only.

## Unresolved questions

No product decisions block the next stage. Platform continuous capture support,
restart restoration details and measured stream behavior still need evidence.

# Stage 2: capture and coordination milestones (partial)

This is a working partial foundation, **not completion of stage 2**. The new
ScreenGate and ScreenView are tested primitives, not yet the Server's active
control/authorization path. Do not start the app/UI migration assuming new
management endpoints exist.

## Implemented

- Capability-driven serializable Screen reference, source status, independent
  activity/control, delivery mode, geometry/image sample, subscription+epoch+frame
  identity, draw acknowledgement, ordered input batch and partial-prefix result.
  No new feature enum or API methods are advertised. Protocol v2 and stage-1
  mismatch handling remain unchanged because existing wire endpoints are unchanged.
- Shared core ScreenSource frame flow implemented by both real sources, without
  wrapping Browser images in ComputerFrame. Computer uses the existing paired
  Device capture RPC/watch lifecycle and honestly reports CaptureRefresh.
- ScreenGate reserves before drain, rejects queued/stale Agent work, grants only
  after a successful drain, and keeps disconnected holders suspended. Expected
  generations and holder identity guard input/release/reacquisition. The gate
  state is read-only to consumers. These semantics are not yet wired into legacy
  Computer/Browser action paths or persisted as authenticated holder records.
- ScreenView retains at most one sent and one latest sample per viewer; checks
  encoded-image budget, epoch/frame/draw ack, geometry and input sequence. It
  consumes a failed batch once and reports the definitely applied prefix, with
  the failing action explicitly outcome-unknown. It is not a transport scheduler.
- Browser uses continuous Page.startScreencast over Playwright's existing managed
  private pipe, with immediate independent Chromium source acknowledgements,
  conflated production/delivery, a 4 MiB encoded-image bound and eight active tab
  streams. Subscribers to the same tab share one CDP stream; every subscription
  checks Agent+Session ownership before accessing the shared flow. Last watcher
  cancellation stops screencast and detaches CDP; tab close also cleans up.
  There is no fixed-frequency screenshot loop for this stream. Existing semantic
  capture remains separate. One Playwright instance and all its contexts/event
  pumping remain confined to the existing single dispatcher. Provisioning now
  serializes installation with an in-process lock and a profile-home file lock.
- Browser persists stable tab ids, order, Agent+Session ownership, safe HTTP(S) or
  about:blank URL (query/fragment removed), and held state. Restoration is lazy
  for a requested owner, after existing Server Session validation on management
  requests; semantic tools already carry their Session owner. Persistent-context
  startup tabs are closed to prevent duplicate/profile-owned auto-restores.
  Held tabs block Agent actions after restart until explicit legacy handback.
  Closed tabs remove records; Agent plugin release removes its saved records.
  Restoration errors remain visible rather than silently replacing saved records.
  Writes use atomic replacement. No input, POST, history, form or JS state replay.

## Source support and limits

DesktopComputer calls cua-driver capture per ComputerRequest. Android's
Computer.android.kt likewise implements request/response capture. Neither paired
Device RPC exposes a push-capture subscription, so this adapter makes no native
continuous-capture claim. No native platform rewrite or device use occurred.

Browser tests demonstrate multiple distinct source frames across visible page
changes while semantic captures/navigation coexist. They also check last-viewer
stream cleanup and restart/close recovery. This does not measure interactive
latency, bandwidth, smoothness, transport head-of-line blocking or remote input.
Synchronous Playwright operations still share one owner thread and can delay
other operations. Stream failures still need source-status propagation through
the eventual management coordinator. Existing legacy tab-list thumbnail capture
is unchanged; visible-only thumbnail subscriptions remain to implement.

## Validation and local commits

- `0bb9007`: shared models, source contract, control/viewer primitives, Computer
  refresh adapter and its test.
- `1ec07f3`: CDP stream production/cleanup, provisioning serialization and owned
  tab restart recovery, with real Chromium integration tests.
- Final follow-up: stop the remaining batch actions after disconnect and record
  this partial handoff. Nothing was pushed and no PR was created.
- No checkout pins existed or were created. The same temporary ignored
  `mise.local.toml` kotlin-task override described in stage 1 was used and removed
  after verification. Kotlin caches required elevated filesystem access; the
  initial sandboxed core command failed before compilation. No approval rejection.
- Browser integration tests now create unique temporary homes/profiles per run.
  No OS HOME changes, installed apps/Server/service/Tailscale operations, mobile
  builds, devices, Mockstro, UI tests or nested delegates. Isolated localhost test
  Servers/Chromium were started and closed by the backend suites.

Final scoped sequential runs (commands are exact):

| Command | Result |
| --- | --- |
| `mise run kotlin -- test -m protocol -p jvm` | 24/24 |
| `mise run kotlin -- test -m core -p jvm --include-classes='*Screen*Test'` | 5/5 |
| `mise run kotlin -- test -m runtime -p jvm` | 84/84 |
| `mise run kotlin -- test -m jvm -p jvm` | 100/100 |
| `mise run kotlin -- test -m client -p jvm` | 1/1 |
| `mise run kotlin -- test -m browser -p jvm` | 4/4 |

The JVM suite completed before final Browser closed-tab stream-cache cleanup
and the core per-action disconnect check. Browser and core suites were rerun
after their respective changes. The core batch test verifies a disconnect during
the first action prevents the remaining actions from executing. Expected failure-path
stack traces appeared in passing Server tests. No test failures. No unscoped
builds or concurrent compiler/test runs occurred. Proof paths are printed by
BrowserRuntimeTest and retained in the system temporary directory. No interactive
latency or device behavior is inferred from these results.

## Required continuation before stage 2 is complete

1. Build the authenticated runtime Screens coordinator and typed shared/client
   calls/events; connect both adapters. Enforce per-Device watch/byte/frame budgets,
   fresh reconnect epochs, revoke/disconnect teardown and draw acknowledgements.
   Separate replaceable frames from the durable response/event/Approval outbox;
   do not promise absolute response priority during a socket write.
2. Make legacy semantic actions, management mutations (including open/close),
   stale Approval bindings and the local phone overlay use the same gate. Add
   authenticated holder/generation persistence and suspended restart recovery;
   the current saved Browser hold is a Boolean, not that new authority record.
3. Route Browser request_takeover through Session questions/Notifications, with
   explicit handback distinct from abandoned/closed questions. The existing
   Computer request_takeover behavior has not been changed in this milestone.
4. Add real management concurrency/revoke/reconnect tests and Browser streaming
   error/status propagation. Confirm deleted-Session record pruning when adding
   Session deletion; the existing management owner guard prevents opening missing
   Sessions, but no new deletion hook was introduced here.
5. Then migrate shared/app and UI; only scoped mobile builds, no device runs.

## Unresolved questions

No new product decision is required. Native push-capture support beyond the
existing RPC, transport scheduling behavior and native mobile runtime behavior
still require implementation/evidence in subsequent work.

# Stage 2 continuation: active backend Screens integration

Implementation commit: `b2b1005` (local only; no push or PR).

The shared seam is now active in Server RPC dispatch, not only isolated source
primitives. The next app/UI stage should use `ScreensClient`. Protocol **3** is
required before advertising `Screens`; old Browser/Computer management mutations
and `computer.watch` return migration errors instead of bypassing generation,
Device ownership or displayed-frame checks. Device-side Computer driver RPC is
unchanged. Existing UI has not been migrated and its old management controls
therefore need the next stage before they work again.

## Active paths

- Management-only `screens.list/watch/unwatch/ack/control/input/open/command`,
  typed frame/status/change events, and typed client calls with bounded waits and
  no mutation retry/replay. Open returns common Screen metadata; commands return
  no screenshot. User Browser mutations no longer capture screenshots for their
  responses. Browser owner validation checks actual Agent+Session and enabled
  tools before source access. Change notifications are management-only.
- Each authenticated connection owns fresh subscription ids/epochs and its own
  ScreenViews. Four subscriptions **per authenticated Device across connections**,
  1 MiB encoded-image budget per frame, one sent and one latest sample per view,
  four replaceable queued images per connection, and 32-action input batches.
  Images bypass the durable response/Approval outbox. The writer checks durable
  responses first and bounds each socket write to 15 seconds; a write already
  underway cannot be preempted. Unwatch/disconnect dispose subscriptions; revoke
  runs the same disconnect teardown. Failed/ended streams invalidate input and
  publish status; failed Browser sources appear failed in that viewer's list.
- Browser selected/thumbnail subscriptions share one CDP producer per tab.
  Selected demand uses every source frame; thumbnail-only demand requests reduced
  quality and every 60th Chromium frame, with at most one delivered thumbnail per
  second. Lists do not capture thumbnails. This is an adaptive production policy,
  not evidence of an achieved frame rate. Computer honestly advertises
  CaptureRefresh, shared per Computer, with a one-second refresh interval.
- Browser semantic tools and management operations share ScreenGate. Computer
  Agent execution and management Screen input/control share its Computer gate;
  local phone overlay observations suspend/clear the local gate. Takeover reserves
  before drain, stale/queued generations are rejected, and timeout never grants
  concurrent input. Explicit active-holder steal requires both the current
  generation and `steal=true`. A disconnected old connection only suspends the
  generation it actually acquired, so it cannot suspend a newer claim.
- Browser gates can be looked up without waiting behind synchronous Playwright
  work once the tab is registered. The actual Browser operations still run on the
  single confined Playwright dispatcher. Semantic observations bind gate
  generations; handback requires fresh capture before later mutations. ToolExecutor
  binds Computer/Browser generation before waiting for Approval and checks it
  after the answer. The existing general Risk/Trust/Deny policy remains in charge.
- Browser restore records now include holder Device identity and generation;
  all saved holds restore suspended until explicit handback/reacquisition. The
  older Boolean migrates conservatively to suspended recovery. Popup holds also
  inherit suspended recovery. Closing marks the gate closed and removes records.
- The shared `request_takeover` tool accepts a Browser tab as well as a Computer.
  Browser requests publish existing Session questions/Notifications with typed
  Screen deep-link metadata. Explicit handback, question abandonment and tab close
  have distinct, conservative results; answering/abandoning the Browser question
  never silently releases its hold. Computer skipped questions retain control.
- Session deletion closes owned tabs and tombstones that owner in the running
  source. Agent Browser disable releases contexts/tabs/records, and authorization
  prevents stale tools from reopening them. Agent deletion also removes its
  Browser profile. Restoration errors remain retained and surfaced.

## Verification and limits

All commands used `KINUVO_HOME=/private/tmp/kinuvo-screens-integration`, the temporary
ignored mise kotlin-task override described above, and `mise run kotlin -- test
-m <module> -p jvm`, sequentially. No pins, OS HOME changes, installed services,
Tailscale changes, other checkouts, mobile builds, devices, Mockstro or UI runs.
Compiler-cache access required filesystem escalation. Temporary test logs are
`/private/tmp/screens-<module>-final.log`; Chromium tests print their unique proof
homes. Initial compatibility/legacy-test failures and compilation fixes were
resolved before final verification; expected failure-path stack traces remain in
passing Server tests.

Final scoped results (full module suites, no class filters):

| Module | Passed |
| --- | --- |
| protocol | 24/24 |
| core | 205/205 |
| runtime | 86/86 |
| browser | 4/4 |
| jvm | 100/100 |
| client | 1/1 |

The final Computer capability-only correction excludes Agent-side zoom/verify_state
from raw source inputs; Agent tools keep those operations. After it, the runtime
suite passed 86/86 again and `test -m jvm -p jvm
--include-classes='*ComputersTest'` passed 13/13. The temporary mise override was
removed after tests.

A self-revoke race surfaced during repeated full Server validation: obsolete queued
events could close a revoked connection before its terminal response. The writer
now discards those events while retaining the terminal response, and validation
waits for that response before teardown. Removing a pending image can leave a
conflated wake notification; the writer treats that as an empty wake, not EOF.
Computer capture failures also end that view and invalidate its input binding.

The real paired Server Browser test exercises management versus ordinary denial,
continuous source-to-RPC frames and draw acknowledgements, ordered input and
single consumption, stale reconnect identities, ordinary RPC responses while a
viewer stops acknowledging, persisted authenticated held restart, explicit steal,
stale-holder rejection, revoke, suspended recovery and tab close. The separate
real Agent run verifies Browser tool dispatch. Core tests cover drain/queued work,
partial input/disconnect and pending Approval invalidation; runtime tests flood the
replaceable lane with 10,000 frames and check shared Device subscription budgets.

Evidence is narrower than exhaustive backend acceptance: the Approval interleaving
is a ToolExecutor/gate test, not a real Browser pending-Approval Server RPC race.
There is no saturated-socket multi-viewer throughput/latency measurement, physical
Computer/phone overlay integration run, or dedicated Server question-to-handback,
Session-delete/plugin-disable/profile-delete race test. The non-acknowledging
viewer RPC test uses a local Browser fixture, not a bandwidth benchmark. Adaptive
thumbnail behavior has source implementation but no measured cadence proof.
Playwright's one thread can delay Browser operations and event pumping during
synchronous navigation/capture. Native push capture, app/UI lifecycle and rendering,
and mobile build/runtime work remain for subsequent stages. Do not interpret these
backend results as proof of physical-device behavior or smoothness.

## Unresolved questions

No product decisions are pending. Remaining evidence work is the unexercised
integration races and performance measurements listed above; the next implementation
stage is shared/app and UI migration followed by scoped mobile builds only.

# Stage 3: shared Live view migration

Local protocol milestone: `16806d7` (`refactor(screens): own portable input and command types`).
App/UI milestone: `989116e` (`feat(screens): migrate shared Live view and responsive controls`).
Evidence notes are committed separately. No push, PR or merge.

## Implementation

- Compose-free `screen/ScreensRepository` and `LiveViewModel` replace the legacy
  Browser/Computer management ViewModels. Production calls use the existing
  ConnectedServer lifetime and typed ScreensClient; a fake repository exercises
  the same interface. One immutable state class separates connection, availability,
  activity, control, selection, incoming/displayed frames, pending work and errors.
- List scope validates Browser Agent+Session ownership while including Computers.
  Current holder identity comes from `devices.list`'s authenticated `current`
  Device, never a guessed local name. Server changes cancel bound observations.
  Selection/reconnect produce new watches; an early frame arriving before the
  watch response is retained only for the returned subscription+epoch. Late
  selection responses are unwatched. Pending watches also clean up after disposal.
- Bitmap and wire frame metadata stay paired. Decoding happens off the UI thread;
  a draw callback acknowledges that exact frame only after Image content draws.
  Input names the acknowledged frame, geometry, holder generation and a monotonically
  consumed batch sequence. Single-flight admission and the shared lock order
  mutations; additional input is disabled while a mutation is pending. No input
  is buffered for replay. Successful input keeps the stream; failed/partial/unknown
  outcomes reconcile the holder and recapture with a fresh watch. The applied
  prefix and an explicit unconfirmed-outcome message remain visible for manual retry.
- Destination visibility stops subscriptions without handing back. Re-entry
  re-subscribes; disconnect preserves the last image as stale and disables input.
  Suspended/foreign holders, explicit steal, drain, handback and unavailable/error
  states have separate presentation. Handback is never coupled to answering or
  skipping a Session question.
- Screen-owned action and command wire types replace ComputerAction/BrowserAction
  in the common input/command interface. Runtime adapters retain existing Agent
  semantic payloads and safety. Computer Agent window/app tools remain unchanged.
  Live view advertises only implemented portable pointer/text/key inputs; it does
  not advertise window management, because no usable common window-list command
  exists. Navigation currently supports address navigation, not history controls.
- Shared Compose LiveViewScreen/LiveCanvas/ScreenOverview/ControlBar/KeyTray use
  Kinuvo tokens/components. Wide Session views can show chat beside the rail and
  large canvas. Phone images use the full available width, with a safe-area Screens
  button, overview bottom sheet, keyboard tray and IME padding. Pinch/two-finger
  movement zooms/pans locally; taps and supported swipes map through the displayed
  image; remote scroll has explicit buttons. Keys are entered as source-supported
  names/shortcuts rather than inferred from a hardcoded OS. Control changes use a
  polite accessibility live region and icon buttons have names.
- Overview subscriptions follow LazyColumn's visible rows and use thumbnail
  watches, capped at three alongside the selected stream. Hidden overview rows
  are unsubscribed; selected and thumbnail frames retain separate identities.
- Existing Computer discovery and Session Browser routes both use LiveViewModel.
  Chat QuestionsAsked deep links carry the requested Screen and reason; Computer
  questions now also carry typed Screen metadata. Old BrowserScreen,
  ComputersScreen, polling ViewModels and synthetic Browser-to-ComputerFrame
  conversions are removed. Device-side ComputerDriver is unchanged.
- Read the iOS SwiftUI integration proof. The existing native shell routes these
  destinations through its full-app Compose host, so no separate Swift state
  observation or embedded-Server branch was added. Compose destination disposal
  stops watches, and ViewModel cleanup handles retained destination destruction.

## Verification

All compiler/test/build processes ran sequentially and were scoped with `-m`/`-p`.
No unscoped build, device discovery/boot, installation, app launch, Mockstro,
standalone Maestro, web preview/server, installed Server/service, Tailscale or
other-checkout operations. No nested delegates or OS HOME changes.

No checkout pins existed or were created. The temporary ignored `mise.local.toml`
only overrode the kotlin task to source `scripts/project-env.sh` then invoke the
unchanged vendor wrapper. Runs used `KINUVO_HOME=/private/tmp/kinuvo-screen-ui`;
backend integration tests created additional unique temporary homes/profiles.
Kotlin caches required filesystem escalation. The first sandboxed app attempt
failed on telemetry cache access before compilation. No automatic approval rejection.
The temporary override is removed before handback.

Scoped commands (prefix `mise run kotlin --`, environment as above):

| Command | Result |
| --- | --- |
| `test -m protocol -p jvm` | 24/24 |
| `test -m core -p jvm` | 205/205 |
| `test -m runtime -p jvm` | 86/86, including final Computer question metadata |
| `test -m browser -p jvm` | 4/4, real managed Chromium fixtures |
| `test -m jvm -p jvm` | 100/100 |
| `test -m jvm -p jvm --include-classes='*ComputersTest'` | 13/13 after question metadata change |
| `test -m client -p jvm` | 1/1 |
| `test -m app -p jvm` | 151/151, including 8 LiveViewModel tests |
| `test -m ui -p jvm` | 90/90 |
| `test -m ui -p jvm --include-classes='*LiveViewScreenTest'` | 1/1 matrix render recheck after visible-row thumbnail changes |

The app tests cover draw-before-input, old reconnect epochs, early watch frames,
foreign scope selection, generation changes, suspended/foreign holders, ordered
successful input sequences, no replay after partial outcomes and destination
watch disposal without handback. Existing ConnectedServer tests cover cancellation
and stale responses across Server lifetimes. These are shared behavior tests,
not a new real Server-to-app UI integration test.

Render matrix: watch, held, suspended, offline and empty, at 390×844 and 1200×850;
held states additionally render the opened keyboard. Tests assert drawing produces
an acknowledgement callback and named keyboard controls open. Paths are under
`shared/ui/build/screens/live-{390,1200}-{watch,held,suspended,offline,empty,keyboard}.png`.
The images use a generated local fixture site, not a live sign-in or real account.
Actually inspected with view_image: `live-390-held.png`, `live-1200-suspended.png`,
`live-390-keyboard.png`, `live-390-empty.png`, `live-390-offline.png`, and
`live-1200-held.png`. Full paths are rooted at this worktree. The desktop fixture
renders the shared Live view; the surrounding real chat pane is compiled, not
pictured by this fixture. Logs: `/private/tmp/screens-final-<module>.log`,
`/private/tmp/screens-ui-final.log`, `/private/tmp/screens-final-computers.log`.

Final scoped build results (all successful, no install/run):

| Command, prefix `mise run kotlin --` | Result / artifact |
| --- | --- |
| `build -m android -p android` | Build successful; `build/tasks/_android_buildAndroidDebug/gradle-project-debug.apk` |
| `build -m ios -p iosSimulatorArm64` | Build Succeeded + Build successful; `build/tasks/_ios_buildIosAppIosSimulatorArm64Debug/bin/Debug-iphonesimulator/Kinuvo.app` |
| `build -m desktop -p jvm` | Build successful, including final shared UI JVM compilation |

Build logs: `/private/tmp/screens-build-{android,ios,desktop}.log`.
The final focused LiveViewScreenTest matrix passed 1/1 again after the connection
route guard and IME inset changes. Android was rebuilt after the route guard so
its artifact matches final source. iOS used exactly the Simulator ARM64 command
above, without booting a Simulator. Existing deprecation/toolchain warnings remain;
there were no final compilation errors. Initial deep-link imports, screenshot
fixture migration and desktop layout receiver errors were fixed. An obsolete
window-capability assertion was updated after intentionally hiding unsupported
window commands. Incremental output briefly retained an earlier input path while
source edits overlapped an earlier run; recompilation and the final frozen-source
app suite verified 151/151, including preservation of the stream after successful
input. No diagnostic input logging remains.

## Remaining limits and unresolved questions

No product clarification is pending. Mobile runtime, physical Computer/phone
input and overlay integration, pinch/IME behavior on hardware, native shell
navigation at runtime and accessibility assistive-technology behavior still need
manual verification. The fixtures do not prove latency, bandwidth, saturated
multi-viewer performance or thumbnail cadence. There is no hover/move queue or
pointer-down/up support. The current UI uses explicit single-flight input rather
than optimistic replay or a deep gesture buffer. Computer window management and
Browser history buttons need additional common commands before they can be exposed.
Browser operations still share the confined Playwright dispatcher; Computer capture
is honestly one-second CaptureRefresh. All earlier backend integration/performance
proof gaps remain as recorded in stage 2. No claim of device behavior or smoothness.

# Live view correction implementation (2026-10-29)

Replaced the full-width debug/control stack with a compact title/subtitle header,
canvas taking all remaining height, canvas-edge control status and state border,
and a phone bottom action bar / compact desktop control row. One contextual
banner carries errors, disconnected recovery or the Agent's request. Desktop
address uses a monospace field; watching is read-only, holder editing is prefilled
and IME Go navigates. Phone subtitle opens that field only for the holder.

Gesture handlers are keyed by Screen, subscription, epoch, control generation and
interaction mode, rather than each decoded frame. The gesture latches the paired
bitmap/sample and local bounds; displayed frames remain latched until completion,
so newer acknowledgements cannot invalidate a gesture mid-touch. Latest-frame
stream buffering remains bounded and existing input validation remains in place.
The UI regression replaces frame and geometry between press and release and
asserts dispatch still uses the first frame. Dragging scroll-capable Screens sends
remote scroll; pinch/pan stays local. Zoom shows a percentage and Fit action.

ScreenInfo now carries nullable sanitized currentUrl from Browser tab metadata;
main-frame navigation publishes change notifications. ScreenCapabilities carries
accepted key/modifier lists. Browser supplies its actual keyboard support;
Computer supplies supported named keys only when its adapter advertises Key and
its known platform accepts them. UI uses these lists without source-kind checks.
Holder-only keyboard tray has text/Send, IME Done and horizontal named buttons;
the arbitrary freeform key field is removed.

Overview now opens/closes on desktop, with a 220dp themed rail, rounded 16:10
thumbnail cards, selection border and human status labels. Phone uses a two-column
sheet and New tab header action. Missing frames keep equal-size placeholders.
Lazy visible indices exclude the trailing creation action; visible subscription
caps and disposal remain unchanged. Shared state records frame receipt time for
the disconnected stale-frame label. Global Live view accepts all Server-returned
Screens, including owned Browser tabs; Session-scoped views still exclude other
owners. Selection rejects Screens absent from the Server list. Tests cover both.

Validation used sequential scoped mise commands and isolated
KINUVO_HOME=/private/tmp/kinuvo-ui-correction. No pins or OS HOME were changed.
Temporary ignored mise.local.toml overrode only the kotlin task and was removed
when finished. Initial sandboxed compilation could not access Kotlin caches;
subsequent checks used elevated compiler-cache access. No device, installation,
app launch, Mockstro, native-capture implementation, push or PR.

- Full UI suite before final layout refinements: 90/90 passed.
- Final focused LiveViewScreenTest: 2/2 passed (render matrix and frame-replacement gesture).
- Full app: 152/152 passed, including global/Session owner isolation.
- Protocol: 24/24 passed.
- Runtime: 87/87 passed, including the parent's
  validScreenAcksDoNotConsumeTheRequestAllowance test; parent files left unstaged.
- Browser: 4/4 passed, including advertised keys/modifiers and real managed Chromium.
- Scoped desktop JVM, Android and iOS Simulator ARM64 builds succeeded.

Rendered matrix under shared/ui/build/screens/: live-{390,1200}-{watch,held,request,
suspended,reconnect,offline,empty,keyboard,overview}.png. Inspected actual PNGs
using view_image: live-390-held.png, live-1200-keyboard.png,
live-390-overview.png and live-1200-overview.png. Selected canvas and overview
cards contain the actual fixture bitmap rather than placeholder text. Inspection
removed a duplicate phone New tab action and reduced desktop control width.
Logs: /private/tmp/correction-*.log and /private/tmp/ui-correction-verified.log.

## Unresolved questions and limits

No product questions. Mobile behavior remains for manual user testing; builds do
not establish runtime behavior. Native Computer continuous capture remains separate
research; existing accessibility refresh fallback is unchanged. Landscape Browser
frames on phones retain substantial letterboxing while the canvas owns the available
height. Detailed Agent step events are not fabricated. Close is offered for the
selected controllable tab; other tabs require selecting and taking control first.

## Final review fixes

Validated Screen draw acknowledgements use a separate 15,000/minute allowance; invalid acknowledgements also consume the ordinary request allowance. The authenticated receive-loop regression passes with the runtime suite (87/87), proving valid frames survive the ordinary limit and the separate acknowledgement cap disconnects excess traffic.

Recorded Screen/Browser terminology and ADR 0017. Migrated the Browser Maestro profile to screens.list/watch/frame/ack/unwatch, protocol 3 and management access, and updated the canvas assertion. Fixture JSON and embedded response/event JSON were parsed and checked locally. These fixture edits have not been run on a device; mobile runtime verification remains manual under the user's build-only scope.

Continuous native Computer capture remains research only. For unattended Android, accessibility capture refresh remains necessary when local MediaProjection consent is unavailable; do not promise automatic projection grants or unattended restart.

Unresolved questions: none for the current implementation. Native capture scope and performance targets require a later decision.
