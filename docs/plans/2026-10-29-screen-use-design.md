# Screen use: one Live view for Computers and Browser — design

Date: 2026-10-29. Design only, nothing implemented. Implementation waits for the user's sign-off. Inputs: [research](2026-10-29-screen-use-research.md) (Codex), current HEAD `0ad0aac`, removed Browser at `5cc083d^`, OpenBot `nightly-labs/openbot@c389f44` (PolyForm Noncommercial: the flow is the reference, no code, styles or assets reused). Mockups: [2026-10-29-screen-use-mockups.html](2026-10-29-screen-use-mockups.html).

Evidence labels follow the research. **Source** means code read at a pinned revision. **Proposal** means this design. Nothing here was built, run or measured.

## 1. What is already decided and what is proposed

**Agreed earlier (source: prior thread pos. 19, research):**

- The Browser is Server-owned. Headless Chromium runs alongside the Server, never a Device's browser.
- Paired Devices with management access can watch and take over. Takeover from the phone is required.
- Private-network sites are allowed. No SSRF claims.
- Browser profiles are persistent and per Agent. Tabs belong to the Agent+Session.
- A sidebar plus Live view is enough. There is no separate Browser app.
- Chromium keeps its sandbox on.
- Implementation starts with `git revert --no-edit 5cc083d`, then reconciliation. Merging the old browser branch alone is not enough.

**Proposed here (needs sign-off):** everything below. Section 16 lists the defaults that matter most. Orchestrator review tightened acquisition, abandoned control and compatibility below; these recommendations supersede the initial Opus summary.

## 2. Terms (CONTEXT.md changes, proposed)

- **Browser**: a Server-owned web browser an Agent browses with. Its tabs belong to a Session, and its sign-ins persist per Agent. _Avoid_: controlled browser, Chromium, Playwright.
- **Screen**: anything Live view shows and Takeover controls. That is a Computer, or one Browser tab. _Avoid_: target, surface, viewport.
- **Computer**: unchanged. A Browser is never a Computer, and its frames are never a `ComputerFrame`.
- **Live view**: now "watching a Screen while an Agent uses it". **Takeover**/**Hand back**: now apply to any Screen.

Code names follow: `Screen*` in protocol, `screen` packages in app and ui. The UI composable is `LiveView…`, not `ScreensScreen`.

## 3. Shape: one deep module, two adapters

```
 Agent tools (computer_*, browser_*, request_takeover)        Management clients (screen.* RPC)
            │ semantic, Approval-gated                              │ user input, control, watch
            ▼                                                       ▼
 ┌──────────────────────────── Screens (harness/server/runtime) ─────────────────────────────┐
 │ registry · per-Screen Gate (control state machine, generation, lease, drain)              │
 │ frame producers (one per watched Screen) · per-client frame lanes (credit, latest-only)   │
 │ Takeover requests → Session questions/Notification · authorization (management, owner)   │
 └───────────────────────────────────────┬───────────────────────────────────────────────────┘
                     ScreenSource seam   │  (2 real adapters + in-memory fake in tests)
              ┌──────────────────────────┴──────────────────────────┐
     ComputerSource (paired Device RPC,                     BrowserSource (Playwright, per-Agent
     accessibility capture/app binding)                     profile thread, DOM ref/revision/origin)
```

The deletion test applies. Today the control, watch, frame-loop and Takeover logic is duplicated, and it differs between `Computers.kt` and `BrowserRuntime`/`BrowserViewModel`. Delete `Screens` and that complexity comes back in both. The interface stays small: list, watch, control, input, command. Each kind keeps its own semantic safety behind its adapter, and nothing gets flattened into generic Agent commands.

## 4. Protocol (illustrative Kotlin, `shared/protocol/src/Screen.kt`)

```kotlin
@Serializable enum class ScreenKind { Computer, Browser }
@Serializable data class ScreenRef(val kind: ScreenKind, val id: String)          // Device id, or tab id

@Serializable data class ScreenOwner(val agentId: String, val sessionId: String) // Browser tabs only

@Serializable sealed interface ScreenAvailability {
    @Serializable @SerialName("ready") data object Ready : ScreenAvailability
    @Serializable @SerialName("offline") data class Offline(val since: Long) : ScreenAvailability
    @Serializable @SerialName("provisioning") data class Provisioning(val step: String, val progress: Float?) : ScreenAvailability
    @Serializable @SerialName("failed") data class Failed(val reason: String, val code: String, val retryable: Boolean) : ScreenAvailability
}

/** Who may send input. Agents act only while [holder] is null. */
@Serializable data class ScreenControl(
    val generation: Long,                 // +1 on every acquire, steal and release
    val holder: ControlHolder? = null,
    val phase: ControlPhase = ControlPhase.Settled,
)
@Serializable data class ControlHolder(val deviceId: String, val deviceName: String, val local: Boolean = false,
    val attended: Boolean = true)         // false: holder's connection lost, lease running
@Serializable enum class ControlPhase { Settled, Acquiring, Releasing }

/** What an Agent is doing right now; independent of control. */
@Serializable data class ScreenActivity(val agentId: String, val sessionId: String, val label: String, val since: Long)

@Serializable data class TakeoverRequest(val id: String, val agentId: String, val sessionId: String, val reason: String, val at: Long)

@Serializable data class ScreenCapabilities(
    val pointer: Set<PointerKind> = emptySet(),   // Tap, DoubleTap, LongPress, SecondaryClick, Drag, Hover
    val scroll: Boolean = false,
    val text: Boolean = false,
    val keys: Set<String> = emptySet(),           // named keys: enter, tab, escape, backspace, arrows, back, home, recents…
    val modifiers: Set<String> = emptySet(),      // shift, ctrl, alt, meta
    val navigation: Set<NavigationKind> = emptySet(), // Address, Back, Forward, Reload  (Browser)
    val tabs: Set<TabKind> = emptySet(),          // Open, Close  (Browser)
    val frames: FrameSupport = FrameSupport(),
)
@Serializable data class FrameSupport(val live: Boolean = false, val maxFps: Int = 4, val maxEdge: Int = 1280)

@Serializable data class ScreenInfo(
    val ref: ScreenRef,
    val title: String,                    // Device name, or page title
    val detail: String? = null,           // platform, or URL without query/fragment
    val owner: ScreenOwner? = null,
    val availability: ScreenAvailability,
    val control: ScreenControl,
    val activity: ScreenActivity? = null,
    val request: TakeoverRequest? = null,
    val capabilities: ScreenCapabilities,
)

/** Frame identity and geometry. Input names the frame it was aimed at. */
@Serializable data class FrameRef(val stream: Long, val seq: Long)
@Serializable data class ScreenFrame(
    val ref: ScreenRef, val frame: FrameRef, val capturedAt: Long,
    val jpeg: String,                     // base64 in text frames (binary later, by capability)
    val width: Int, val height: Int,      // encoded image pixels
    val space: CoordinateSpace,           // what input maps onto: device px, desktop points, CSS px
)
@Serializable data class CoordinateSpace(val width: Int, val height: Int, val unit: SpaceUnit, val rotation: Int = 0)

/** Positions are fractions 0..1 of the frame image, after the client removes letterbox/zoom/pan. */
@Serializable sealed interface ScreenInput {
    @Serializable @SerialName("pointer") data class Pointer(val kind: PointerKind, val x: Float, val y: Float, val toX: Float? = null, val toY: Float? = null) : ScreenInput
    @Serializable @SerialName("scroll") data class Scroll(val x: Float, val y: Float, val dx: Float, val dy: Float) : ScreenInput
    @Serializable @SerialName("text") data class Text(val text: String) : ScreenInput
    @Serializable @SerialName("key") data class Key(val key: String, val modifiers: Set<String> = emptySet()) : ScreenInput
}
@Serializable data class ScreenInputParams(val ref: ScreenRef, val generation: Long, val frame: FrameRef,
    val seq: Long, val inputs: List<ScreenInput>)       // seq of first input; batch is ordered
@Serializable data class InputAck(val applied: Long, val outcome: InputOutcome, val message: String? = null)
@Serializable enum class InputOutcome { Applied, StaleFrame, NotHolder, Unsupported, Unavailable, Unknown }

@Serializable sealed interface ScreenCommand {          // user-only, capability-gated
    @Serializable @SerialName("navigate") data class Navigate(val url: String) : ScreenCommand
    @Serializable @SerialName("history") data class History(val kind: NavigationKind) : ScreenCommand
    @Serializable @SerialName("open_tab") data class OpenTab(val owner: ScreenOwner, val url: String?) : ScreenCommand
    @Serializable @SerialName("close_tab") data object CloseTab : ScreenCommand
}

/** A source-level status (e.g. Browser installing) that isn't one Screen. */
@Serializable data class ScreenSourceStatus(val kind: ScreenKind, val availability: ScreenAvailability)
@Serializable data class ScreensSnapshot(val screens: List<ScreenInfo>, val sources: List<ScreenSourceStatus>)
```

**Methods/events (additive, `Api.kt`):**

| Name | Params → result | Notes |
| --- | --- | --- |
| `screens.list` | `ScreenScope(agentId?, sessionId?)` → `ScreensSnapshot` | Management only. Scope filters Browser tabs. Computers are always listed. |
| `screen.watch` | `WatchParams(ref, quality: Live\|Thumbnail, watch)` → `ScreenInfo` | Starts or stops frames for this connection. Idempotent. |
| `screen.ack` | `(ScreenRef, FrameRef)` → `Unit` | Frame credit: "drawn". |
| `screen.control` | `ControlParams(ref, take, expected: Long?)` → `ScreenControl` | `expected` = generation the user saw. A steal needs `take=true` with the current generation. |
| `screen.input` | `ScreenInputParams` → `InputAck` | Never retried by transport. |
| `screen.command` | `ScreenCommandParams(ref, command, generation)` → `ScreenInfo` | Navigation needs control. `OpenTab` starts under the caller's control. |
| event `screens.changed` | `ScreensSnapshot` | Management clients only. Coalesced per 250 ms. |
| event `screen.frame` | `ScreenFrame` | Sent through the frame lane (§8), never the outbox. |

**Compatibility.**

1. Hazard: `ServerFeature` is a plain enum inside a `Set`, so an old client decoding an unknown value such as `Screens` or `Browser` throws (source: `Capabilities.kt`, no lenient decoding in `ProtocolJson`). Ship lenient feature decoding for future evolution, but it cannot repair already-installed clients. Before advertising new enum values (including in the Browser restoration), either negotiate capabilities using a request shape old clients understand and filter the response, or explicitly raise the protocol compatibility range so old clients receive the existing typed incompatibility response before feature decoding. Recommended for this change: an explicit compatibility break with the existing update flow; do not promise old management clients work across it. Test an actual old-client payload/handshake, not only the new decoder.
2. A new `ServerFeature.Screens` plus `ServerFeature.Browser`. Per-Screen `ScreenCapabilities` defaults are "nothing", so a missing field never means "supported".
3. The legacy `computers.list` / `computer.watch/input/takeover` and events may stay as thin mappings for one release, but do not override the negotiated protocol compatibility range. A legacy input must never borrow the current holder's generation or bypass ownership: reject it when another Device holds control; require an authenticated acquisition first. Legacy Device-side drivers are a separate compatibility concern from old management clients.
4. Device-side `computer.act`/`ComputerRequest`/`ComputerResult` stay as they are. Old Device drivers keep working, and `ComputerSource` adapts them.
5. A new client on an old Server (no `Screens`): Live view shows "Update this Server to use Live view". There is no dual-path ViewModel (sign-off question 6).

## 5. Server internals and ownership

```kotlin
// harness/core/src/Screens.kt — the seam. Two production adapters + FakeScreenSource in tests.
interface ScreenSource {
    val kind: ScreenKind
    val status: StateFlow<ScreenSourceStatus>
    val screens: StateFlow<List<ScreenEntry>>             // ref, title, detail, owner, availability, capabilities
    suspend fun frame(id: String, quality: FrameQuality): CapturedFrame   // image + CoordinateSpace
    suspend fun input(id: String, input: ScreenInput, space: CoordinateSpace): InputOutcome
    suspend fun command(id: String, command: ScreenCommand): ScreenEntry?
    suspend fun controlChanged(id: String, user: Boolean)  // invalidate refs/captures, keep-awake, local release
    val localControl: Flow<Pair<String, Boolean>> get() = emptyFlow()   // phone overlay Takeover/handback
}

// harness/server/runtime/src/Screens.kt — the deep module.
class Screens(sources: List<ScreenSource>, questions: …, log: SessionLog, clock: Clock) {
    fun snapshot(scope: ScreenScope): ScreensSnapshot
    suspend fun <T> agent(ref: ScreenRef, run: RunContext, label: String, block: suspend () -> T): AgentOutcome<T>
    suspend fun control(ref: ScreenRef, take: Boolean, expected: Long?, caller: DeviceIdentity): ScreenControl
    suspend fun input(params: ScreenInputParams, caller: DeviceIdentity): InputAck
    suspend fun command(params: ScreenCommandParams, caller: DeviceIdentity): ScreenInfo
    fun watch(ref: ScreenRef, quality: FrameQuality, lane: FrameLane); fun unwatch(…); fun ack(…)
    suspend fun requestTakeover(ref: ScreenRef, run: RunContext, callId: String, reason: String): TakeoverOutcome
    fun detach(device: DeviceIdentity)                    // disconnect/revoke → lease, unwatch
}
```

| Owner | Responsibility |
| --- | --- |
| `Screens` (runtime) | Control state machine, generation, leases, drain, frame producers and lanes, request_takeover, Notifications, `screens.changed`. |
| `ComputerSource` (runtime, from `Computers.kt`) | Paired-Device RPC, platform/capability probing, coordinate space (screenshot px, desktop points for windows), and the existing capture/app/password binding. |
| `Computers` plugin tools | `computer_list/capture/act/apps/windows`. They are unchanged except that they run through `screens.agent(...)`. The Risk/app Trust logic stays here. |
| `BrowserSource` (`harness/plugins/browser`) | One dispatcher thread **per Agent profile** (not one global thread), tabs, DOM refs/revision/fingerprint, URL policy, provisioning, frames, user input mapping (CSS px). |
| `Browser` plugin tools | `browser_*` semantic tools (§10) through `screens.agent(...)`. |
| `ServerRuntime` | Auth: management for all `screen.*`, and holder identity = authenticated Device id from the connection token, never from params. It also owns the frame lane per client, and `detach` on disconnect/revoke. |

**Per-Agent profile threads.** A 30 s navigation in one Agent's profile no longer blocks another Agent's Takeover or frames. Within one profile, Agent navigations use `waitUntil=commit` plus short sliced waits. That frees the thread between slices, so drain and frames for sibling tabs keep moving.

## 6. Control state machine

Server state per Screen. `phase` and `holder` are on the wire, and Agent activity is separate.

```
            take (gen+1)                     drained
  Free ───────────────────▶ Acquiring ───────────────────▶ Held(holder, attended)
   ▲                          │  in-flight Agent step         │   │  holder disconnect/revoke
   │                          │  finishes or times out        │   ▼
   │                          │  (no new Agent step starts)   │  Held(attended=false, lease)
   │                          │                               │   │ unattended (15 min)   
   │   explicit hand back     ▼                               │   │ or another Device steals (gen+1)
   └──────────────────────── Releasing ◀──────────────────────┘◀──┘
           refs/captures invalidated, waiters woken (they must recapture)
```

- **Gate.** There is one `Mutex` per Screen around every Agent step and every user input. `agent()` checks control *inside* the gate and waits while it is held. Acquire sets `Acquiring` before it takes the gate, so no new Agent step starts. It then waits for the in-flight step: up to 15 s, or 35 s for a Browser navigation. If that bound passes, remain Acquiring and keep user input disabled. Report "Waiting for the Agent's last step to stop" with a retry/stop action only when the adapter can prove quiescence. A cancellation request or a timed-out RPC is not proof: do not release the gate or grant user input until the adapter confirms that no queued or dispatched action can still execute. An unknown outcome is surfaced for inspection, never replayed. Adapters that cannot establish quiescence leave control blocked rather than permit concurrent input.
- **Release.** Set `Releasing`, take the gate, call `controlChanged(user=false)`, which invalidates refs, captures and the Browser revision, set Free, then wake waiters. This fixes the research's risk 1, where today `controls.update` runs before the release request holds the mutex.
- **Generation.** Every input and command carries the generation the client last saw. A mismatch returns `NotHolder` with the current control, so a Device whose control was stolen can never inject input.
- **Exclusive holder.** One Device at a time. Another Device sees "Controlled from Desk Mac" and an explicit **Take over here**, which is a steal with `expected` = the current generation. The previous holder sees "Desk Mac took over" and its input tray disables.
- **Abandoned Takeover.** On holder disconnect, app background past 60 s, or revoke, the hold becomes `attended=false`. Agents keep waiting, because resuming mid sign-in is unsafe. Any management Device may take over here or hand back. After **15 min** unattended, mark the Takeover request `abandoned` ("The user didn't finish; ask before continuing"), but retain a suspended hold. Do not wake ordinary queued Agent actions or automatically resume after a timeout, app backgrounding or Server restart. A management Device must explicitly hand back or take over here. The abandoned state survives reconnect; if recovery cannot restore control safely after Server restart, require an explicit recovery decision before Agent actions resume. Revoke also stops that Device's watches immediately.
- **Local Takeover.** The phone overlay on the Computer itself becomes `Held(holder = that Device, local = true)` through `localControl`. Hand back can come from either side. This is today's behavior, made explicit.

**Agent waiting.** Agent tools wait while a Screen is held, as Computer does today. A wait longer than **30 min** returns a structured `paused` result ("The user still controls X; ask before continuing"), which matches the Routine Approval skip window. While a Screen is held, any Approvals already pending stay pending. If one is approved, it is rechecked after release (§9).

## 7. Frames: bounded JPEG first, video later only on measured need

**Recommendation: no true video in the first iteration.** Use credit-based JPEG at an adaptive rate:

- **Live:** up to 4 fps, longest edge ≤ 1280, q≈60. Typical size is 60–120 KB, so ≤ ~0.5 MB/s at the cap.
- **Thumbnail:** one frame per 3 s, edge 320, q≈35. Only visible cards are watched.

Video (WebRTC/H.264) means TURN/NAT, codecs on four platforms, blurrier UI text and much more code. Most of the latency here is the capture itself: an accessibility screenshot or Playwright screenshot costs tens to hundreds of ms. A codec would not remove that.

**Revisit trigger (measured, not assumed):** p50 input→next-frame above 400 ms on LAN or 1 s remote, or above 2 Mbps sustained on Live. The Browser's first upgrade would then be a CDP screencast through Playwright's private-pipe `CDPSession`, which is still JPEG and already per-frame acked. There is still no public endpoint.

**One producer per watched Screen.** It runs at the maximum rate any watcher demands. It pauses when nobody watches and after an Agent step that already produced a frame. It also yields to input: a capture never starts while user input is queued on the gate.

## 8. Streaming and backpressure, outside response delivery

Today's watcher pushes base64 frames into the general `outbox` (1024 frames) via `offer`. A full outbox **disconnects the client** (source: `ServerRuntime.kt:253-258`), so a slow phone can lose its Approvals because of video.

Proposal: give each client connection a **`FrameLane`**. It holds the latest frame per watched Screen (conflated; older frames are dropped, never queued) and **credits** (window 2 per Screen, replenished by `screen.ack`).

The writer loop selects the outbox first and the lane only when the outbox is empty. Frames cannot build an outbox backlog or trigger outbox overflow. An already-writing frame can still delay a response on the same socket, so cap frame size, watch count and write time as well as bytes in flight; lane pressure itself must never disconnect anyone. An unacked Screen simply gets fewer frames. The thumbnail lane gets a smaller total byte budget (≤ 256 KB in flight per client).

Binary WebSocket frames are a later, capability-advertised optimization (`FrameSupport.binary`). They are not needed for correctness.

## 9. Input ordering, reconnect and Approval freshness

**Client input queue** (`shared/app`):

- Pointer moves and scroll deltas coalesce. Press, release, text and keys flush the queue in order.
- Each batch names `FrameRef` (the frame **displayed** when the gesture began) and a monotonic `seq`.
- The server rejects input whose `FrameRef` geometry it no longer holds (it keeps the last 8 per stream) with `StaleFrame`. It never clamps a point onto another control.

**Reconnect, with no replay:**

1. When the connection drops, pending unacked inputs are dropped, not resent. If any were in flight, the UI shows "Your last input may not have reached Desk Mac. Check the screen before repeating."
2. On reconnect: re-list, re-watch (the new `stream` epoch makes old geometry invalid), and re-read control. Input stays disabled until **a fresh frame from the new stream** has arrived *and* control has been reconciled. If the user still holds the Screen (lease within grace), they continue with the same generation.
3. Navigation, form submits and typing are never retried by the transport. On timeout the outcome is `Unknown`.

**Approval freshness.** Approvals resolve outside the gate. When the approved tool executes, inside `screens.agent()`, it rechecks:

- control is free;
- the run's owner still owns the Screen (Browser: Agent+Session);
- the capability is still advertised;
- the binding still matches. Computer: capture id + foreground app + password flag (existing). Browser: revision + page fingerprint + **origin**.

Hand back invalidates captures and revisions, so an Approval granted before a Takeover fails freshness with "The screen changed since your Approval; capture again". Approved actions are never replayed automatically.

## 10. Agent tools stay semantic and distinct from user input

Agents never get a generic `screen_input` tool. User input (`screen.input`) and Agent tools meet only at the gate.

- **Computer:** today's tools, unchanged in arguments, Risk and app Trust rules.
- **Browser** (restored, then extended):
  - `browser_tabs`, `browser_open`, `browser_capture`, `browser_navigate`, `browser_click`, `browser_type`, `browser_scroll`, `browser_wait`, `browser_close`;
  - **new** `browser_key` (named keys only: enter, tab, escape, arrows, on a focused ref from the current revision), `browser_back`;
  - **request_takeover**, generalized (below).
  - There is no `evaluate`, no upload and no downloads.
- **Proposed Browser Trust rules per site.** Assessment names the origin host, e.g. `browser_click bank.example`, like the per-app `computer_act <app>` rules. Password fields, payment-looking fields (`autocomplete=cc-*`, `one-time-code`) and `type=password` are refused to Agents and can never be trusted. The Agent is told to request a Takeover instead.
- **`request_takeover(screen?, reason)`** moves from `Computers` into `Screens.requestTakeover`:
  1. It sets `ScreenInfo.request`.
  2. It publishes the existing Session `QuestionsAsked`, so a Notification goes out when nobody is watching. The deep link opens `Route.LiveView(session scope, ref)`.
  3. It completes on hand back (`done`), on skip (`skipped`) or on unattended expiry (`abandoned`).
  - A Browser tab defaults to the Agent's current tab, a Computer to the only available one.

## 11. Browser lifecycle, profiles, provisioning, restart

| Event | Behavior |
| --- | --- |
| First Browser use | The source goes into `Provisioning(step, progress)`: download Chromium, then launch. A failure is `Failed(reason, code, retryable)` with a **Retry** action. Tabs elsewhere are not blocked (per-profile threads). The install log path is shown in Settings, not in Live view. |
| Linux launch blocked by AppArmor | `Failed(code="userns_restricted")` with operator guidance (below). The sandbox stays on, and we never add `--no-sandbox`. |
| Session deleted/archived | Its tabs close. |
| Idle tab (no Agent step, not held, not watched, for 2 h) | Closed, and listed in the Session as "Browser tab closed after 2 h idle". Cap of 16 tabs per Session and 64 per Server. |
| Agent's Browser tool turned off | Contexts and tabs close, and the profile stays on disk (existing `release`). |
| **Reset Browser data** (Agent settings, confirm) | Closes tabs and deletes the profile directory. |
| Agent deleted | The profile is deleted with the Agent. |
| Server restart | **Tabs are lost and sign-ins persist.** On the next use the Agent sees no tabs, and the Session shows "Browser tabs closed when the Server restarted". We don't restore tabs (unlike OpenBot): cookies surviving is a different promise from page state surviving. |
| Popup | It becomes a new tab owned by the same Agent+Session and inherits control (a held opener gives a held popup). |
| Held tab closed by the page | The hold ends, and `request_takeover` returns `closed` with "the tab closed; completion unconfirmed". Closing a page is not proof the requested sign-in/payment completed. |

**Takeover scope:** per tab. Other tabs in the same Agent profile keep running, even though a sign-in changes shared cookies (sign-off question 3).

**Ubuntu 24.04 operator note** (goes in `docs/controlled-browser.md`). Chromium's sandbox needs unprivileged user namespaces, and AppArmor restricts them by default on 24.04. There are two options, both administrator-managed, and Kinuvo never changes either:

- **Preferred:** an AppArmor profile with `userns,` for the managed Chromium path.
- **Global:** `sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0` for the current boot. To persist it, put it in `/etc/sysctl.d/60-apparmor-namespace.conf`. Remove that file and reboot to undo it. This loosens the host's restriction; it does not disable Chromium's sandbox.

## 12. Client, ViewModel and UI

**`shared/client`:** typed calls only, plus a `screenFrames` event flow. No retries for `screen.input`/`screen.command`. Typed wire calls stay in `shared/client`; `shared/app` composes them through its repository. Bounded timeouts are reused from `ControlledBrowser` (provisioning open: 360 s).

**`shared/app/src/screen/`:**

- `ScreensRepository`, on `ConnectedServer`. It reference-counts watches per (ref, quality), so a thumbnail card and the Live view share one subscription. It also sends acks after the UI reports "drawn", re-watches on reconnect and drops everything on Server switch.
- `ScreenInputQueue`: coalescing, seq, FrameRef, unknown-outcome tracking.
- `LiveViewModel`:

```kotlin
data class LiveViewState(
    val scope: ScreenScope,                 // all Screens, or one Session's
    val screens: List<ScreenInfo>? = null,  // null while loading
    val sources: List<ScreenSourceStatus> = emptyList(),
    val selected: ScreenRef? = null,
    val frame: ShownFrame? = null,          // image bytes, FrameRef, geometry, receivedAt
    val link: Link = Link.Connected,        // Connected, Reconnecting(since), Lost
    val pending: ControlPhase? = null,      // this Device's acquire/release in flight
    val unknownInput: Boolean = false,
    val error: String? = null,
) {
    val screen get() = screens?.firstOrNull { it.ref == selected }
    val fresh get() = frame != null && link == Link.Connected && frame.stream == currentStream
    val canInput get() = fresh && screen?.control?.holder?.deviceId == thisDevice && pending == null
}
class LiveViewModel(route: Route.LiveView, connection: ConnectedServer, device: DeviceIdentity) : ViewModel() {
    val state: StateFlow<LiveViewState>
        field = MutableStateFlow(LiveViewState(route.scope, selected = route.screen))
    fun select(ref: ScreenRef); fun takeOver(); fun takeOverHere(); fun handBack()
    fun pointer(kind: PointerKind, x: Float, y: Float, toX: Float? = null, toY: Float? = null)
    fun scroll(x: Float, y: Float, dx: Float, dy: Float); fun type(text: String); fun key(key: String, modifiers: Set<String> = emptySet())
    fun navigate(url: String); fun history(kind: NavigationKind); fun openTab(url: String?); fun closeTab()
    fun drawn(frame: FrameRef)              // UI calls after the bitmap is on screen → ack
}
```

This replaces `ComputersViewModel` and the restored `BrowserViewModel`. 750 ms polling is gone: frames are pushed and the list is event-driven. Embedded Servers get no special case.

**`shared/ui/src/screen/`:**

- `LiveViewScreen`: responsive.
- `ScreenOverview`: thumbnail cards, as a rail, grid or sheet.
- `LiveCanvas`: decodes off the main thread and pairs bitmap with FrameRef *atomically*, so the old bitmap never shows with new geometry. It handles letterbox, zoom and pan, and reports `drawn`.
- `ControlBar`, `KeyTray` (capability keys only), `AddressBar` (when `navigation` is advertised), `TakeoverRequestCard`.

**Routes:**

- `Route.LiveView(scope, screen?)`. `Route.Computers` maps to `LiveView(all)`.
- Chat header button "Live view" → Session scope. The `request_takeover` card's "Open Live view" goes to the requested Screen.
- The Computers sidebar entry becomes **Live view**. The label "Computers" stays as a section inside it.

**Layout** (see mockups):

- **Desktop ≥ 900 dp:** conversation | contextual overview rail (cards for this Session's tabs plus Computers, each showing title, host, control state and live thumbnail) | large Live view.
  - The Live view header shows the title and host, an address and history row (Browser only), the control chip and **Take over / Hand back**.
  - The canvas edge colour encodes control: muted while watching, accent while the Agent acts, warning while a Takeover is requested, primary while you control it.
  - The Agent's current step label is pinned to the canvas ("Clicking “Continue”").
- **Phone:**
  - Full-width canvas, with a top bar showing Back to conversation, title and host, and a Tabs button with a count.
  - A bottom, safe-area control bar: one primary action (Take over / Hand back), a Keyboard toggle, and a Tabs sheet with the overview grid.
  - The keyboard tray sits above the system keyboard: a text field that sends on Return, plus a capability-key row (Tab, Esc, arrows, ⌫, Back/Home on Android).
  - Pinch zooms the view. In control, one finger taps and drags, and a two-finger drag is remote scroll.
- **States:** Agent working, Takeover requested, acquiring (waiting for the Agent's step), you control it, controlled elsewhere, unattended, handing back, reconnecting (dimmed stale frame, "last frame 12 s ago", input off), unknown input outcome, offline Computer, Browser installing/failed, no Screens, revoked.

**iOS:** host the Compose `LiveViewScreen` per `apps/ios/README.md#swiftui-integration-proof`. The native destination owns the ViewModel lifetime and disposes watches on disappear.

## 13. Layer migration and file mapping

| Layer | Now | After |
| --- | --- | --- |
| Protocol | `Computer.kt` (+ restored `Browser.kt`) | **new** `Screen.kt`. `Computer.kt` stays for the Device RPC and legacy. `Browser.kt` is reduced to the Agent-side tool payloads (`BrowserFrame` for model rendering), and the `BrowserCommand` user path is removed. `Api.kt` gets `screen.*` and lenient `ServerFeature` decoding. |
| Core | restored `harness/core/src/Browser.kt` | `harness/core/src/Screens.kt` (`ScreenSource`, `ScreenEntry`, `CapturedFrame`). `BrowserService` folds into `BrowserSource`. |
| Runtime | `Computers.kt` (control, watch, tools mixed) | `Screens.kt` (deep module), `ComputerSource.kt`, and `Computers.kt` tools only. `FrameLane` lives in `ServerRuntime.kt`, the `screen.*` handlers sit next to the computer handlers, and legacy handlers map onto `Screens`. |
| Browser plugin | restored `BrowserRuntime.kt`, `BrowserPlugin.kt` | `BrowserSource.kt` (per-profile thread, frames, user input, commands, provisioning status), `BrowserPlugin.kt` (semantic tools, origin Trust, request_takeover via Screens). |
| JVM host | `Plugins.kt` | registers `BrowserSource` with `Screens`. |
| Client | restored `ControlledBrowser.kt` | replaced by typed `ScreensClient` calls in `shared/client`; `ScreensRepository` in app composes them. |
| App | `computer/ComputersViewModel.kt`, restored `browser/*` | `screen/ScreensRepository.kt`, `ScreenInputQueue.kt`, `LiveViewModel.kt`. `ComputerDriver.kt` stays on the Device side. |
| UI | `computer/ComputersScreen.kt`, restored `browser/BrowserScreen.kt` | `screen/LiveViewScreen.kt`, `LiveCanvas.kt`, `ScreenOverview.kt`, `ControlBar.kt`, `KeyTray.kt`. Changes also in `KinuvoApp.kt` routes, `Shell.kt` sidebar entry, `ChatScreen.kt` header and Takeover card, and the Agent settings "Reset Browser data". |
| Docs | ADR 0010, CONTEXT | ADR 0017 "Screens: one control model, two sources", the CONTEXT terms (§2), and the Ubuntu note in `docs/controlled-browser.md`. |

## 14. Tests and evidence plan

Every run is scoped, one build at a time, and on designated devices only. None exist yet.

| Layer | Command | What it proves |
| --- | --- | --- |
| protocol | `mise run kotlin -- test -m protocol -p jvm` | Round-trips, lenient unknown `ServerFeature`, legacy shapes unchanged. |
| runtime | `mise run kotlin -- test -m runtime -p jvm` (via the `FakeScreenSource`) | Acquire drains the in-flight step; release interleavings (Agent queued at release, Approval resolved during hold → freshness failure); generation steal → old holder `NotHolder`; disconnect → unattended → 15 min abandonment with hold retained; drain timeout never enables concurrent input; revoke stops watches; lane never disconnects under a 10k-frame flood while responses are delivered in order; StaleFrame on old geometry; no replay after reconnect. |
| server jvm | `mise run kotlin -- test -m jvm -p jvm` | `ComputersTest` kept, plus restored `BrowserServerTest`; paired management vs ordinary denial on `screen.*`; legacy `computer.*` clients. |
| browser plugin | `mise run kotlin -- test -m browser -p jvm` | Real sandboxed Chromium (CI already has the step): per-profile thread isolation, origin binding, password and OTP refusal, popup inherits control, idle close, reset deletes profile. |
| app | `mise run kotlin -- test -m app -p jvm` | `LiveViewModel` with fake transport: input disabled until fresh frame + reconcile, coalescing/flush order, unknown-outcome flag, select race, Server switch disposes. |
| ui | `mise run kotlin -- test -m ui -p jvm` → `shared/ui/build/screens/` | Screenshots of every state in §12 at phone 390 dp and desktop 1280 dp. |
| mobile E2E | `./mockstrow --dev --platform android\|ios --tags smoke,regression --device <designated>` | Rewritten `.maestro/flows/mobile/live-view.yaml` with wiremock stubs (tabs sheet, Take over, keyboard tray, Hand back). Mocked proof only. |
| iOS build | `mise run kotlin -- build -m ios -p iosSimulatorArm64` | Native host compiles. |

Reports keep three kinds of proof apart: source assertions, mocked UI and real runtime. Real-Chromium-on-a-phone proof needs a real Server, and only on request.

## 15. Stacked PRs

Before these PRs, settle protocol negotiation/versioning for restored Browser feature advertisement. Every layer targets the previous layer, with the base against main; no layer merges without user approval.

1. **Restore Browser**: `git revert --no-edit 5cc083d`, then reconcile with the current harness, API and composition. Restored tests pass. No redesign; feature advertisement must obey the compatibility decision above.
2. **Protocol + lenient features**: `Screen.kt`, `screen.*` declarations, lenient `ServerFeature` decoding. Not advertised yet.
3. **Screens core + Computer source**: the gate, state machine, leases, generation, request_takeover moved, legacy `computer.*` mapped. FakeScreenSource tests.
4. **Frame lane**: credits, conflation and writer priority in `ServerRuntime`, and Computer frames moved to the lane.
5. **Browser source**: per-profile threads, user input/commands via Screens, origin Trust, `browser_key/back`, provisioning status, lifecycle rules. The `browser.command` user path is removed. Advertise `Screens` + `Browser`.
6. **App**: `ScreensRepository`, `ScreenInputQueue`, `LiveViewModel`. Removes `ComputersViewModel`/`BrowserViewModel`.
7. **UI**: Live view redesign, routes, chat entry points, screenshot tests, Maestro flow.
8. **Docs + iOS host**: CONTEXT, ADR 0017, the Ubuntu note, iOS destination.

## Orchestrator review constraints before implementation

- `ScreenSource` must expose optional window operations and metadata as advertised capabilities, alongside tabs; preserve existing Computer window/app semantics. These are missing from the illustrative command sketch and must be included before freezing the protocol. No UI switch on kind to infer a capability.
- All mutating commands, including tab creation/closure, must use the same authorized control gate. Creating a tab needs a source/owner-scoped command shape (no fabricated existing ScreenRef); close invalidates watches/control and reports completion unconfirmed. Stealing/releasing requires the expected generation and an explicit caller intent, never a stale Boolean.
- Frame ids must be unique per connection subscription and Screen. Ack, displayed-frame binding, selection and epoch all need explicit identity. Define scroll units and transform mapping; geometry freshness alone does not prove page content is unchanged.
- Input batching reports the exact applied prefix on partial failure; consume sequence numbers at most once within an epoch, while never replaying unknown-result batches. Unsupported pointer down/up, modifier holds, or release-on-disconnect semantics must not be advertised by an adapter.
- Profile threading must respect Playwright thread confinement: independently create/own Playwright resources per dispatcher, or use one well-defined owner loop with nonblocking operations. Do not move one shared Playwright instance across arbitrary Agent threads. Provisioning needs one safe installation coordinator.
- Per-site Trust must bind scheme, host and port, and recheck after redirects. Password/OTP/card heuristics are limited: they cannot promise all sensitive fields are detectable. Keep existing Risk/Approval enforcement and block known sensitive targets, including key-based routes that would bypass refusal.
- Streaming rates and byte sizes above are targets to measure, not performance evidence. Start with one bounded live stream and visible-only thumbnails; measure bandwidth/latency on the supported client platforms.

## User decisions after design review

These decisions supersede conflicting proposals above. Implementation has not started; the remaining control/streaming decisions and overall sign-off are pending.

- **Takeover scope:** per tab. Other tabs may keep working, including tabs sharing the Agent's sign-ins.
- **Restart:** prefer restoring tabs where feasible. Persist Agent+Session ownership, stable tab identity, URL and ordering alongside the existing persistent profile. Restore by opening a saved safe URL, never by replaying inputs, form submissions or navigation history. Do not claim to recover unsaved form state, POST results, JavaScript state or exact page state. A held tab restores suspended until explicit handback; restoration failure is visible. Confirm Playwright profile startup behavior to avoid duplicate/restored tabs or ownership mixing. Closing a tab removes its restore record; deleted Sessions/Agents must not resurrect tabs.
- **Browser restrictions:** no additional per-site Trust rules or Browser-specific password/payment/OTP refusals for this iteration. This includes removing the restored baseline's password-field refusal. Existing Kinuvo Agent Risk tolerance and Approval machinery remain the general execution policy; do not introduce a separate Browser policy. Management authentication, Agent+Session ownership, Takeover exclusion and input freshness still enforce correct routing/control. Do not log sensitive typed values. Broader upload/download/evaluate tool scope is not implied by removing sensitive-field restrictions.
- **Compatibility:** backward compatibility may break; the user is the sole user. Prefer a clean shared interface and an explicit protocol compatibility update over legacy management mappings or a dual-path ViewModel. Retain unchanged Device-side driver RPC where useful, without promising compatibility across a bumped protocol range. Update incompatible-version handling/tests before advertising new feature enum values.
- **Mobile verification:** scoped builds only; the user will test mobile manually. No device assignments or Mockstro runs are required for this task. Report which platforms actually built, and any unavailable platform/toolchain. Do not claim device behavior from build results.

## Approved implementation direction

The user approved implementation and continuous streaming on 2026-10-29. This section supersedes the original 4 fps screenshot-first proposal and any prior pending sign-off language.

- **Selected Browser tab:** continuous Chromium CDP screencast from the outset, through the managed private connection. Adaptive quality/delivery and bounded latest-frame transport; no arbitrary 4 fps cap. Keep capture/Agent observations separate from the user stream. A source ack releases Chromium production credit; client draw acks manage delivery independently, so one stalled viewer cannot block others or semantic tools.
- **Selected Computer:** continuous platform capture where available behind the same streaming contract; explicit screenshot-refresh fallback where a platform/driver lacks continuous capture. Inspect actual driver capabilities and do not advertise polling as continuous streaming. Report any platform support gaps.
- **Thumbnails:** low-frequency, visible-only capture. Do not start full-rate streams for overview cards.
- **Shared stream contract:** subscription start/stop, frame identity and coordinate mapping, source delivery mode/capabilities, bounded backpressure, reconnect epochs and priority for control/input/responses. A new source can supply a push flow or an honest capture fallback behind one interface.
- **Takeover on disconnect:** keep the selected tab paused until explicit handback, including beyond 15 minutes. User approved this policy. Persist suspended recovery for restored held tabs.
- **Validation:** scoped tests/builds sequentially, plus phone/desktop rendering checks. Mobile device use is left to the user; no E2E/device runs. Measure Browser production and delivery where feasible; do not claim real-time latency or smoothness without measurements.
- **Delivery:** Codex Personal implementation delegates (Codex Tapadoo fallback if usage exhausted and provider available); Opus owns design. Restore Browser first with git revert --no-edit 5cc083d, then implement the shared seam and UI. Open reviewable PRs; never merge without asking.

## 16. Unresolved questions

No product decisions block implementation. Resolve platform streaming availability and tab restoration details from source/runtime evidence, and report material limitations. Browser Risk tolerance uses the existing Agent policy without new Browser-specific restrictions. Mobile runtime behavior will be tested by the user.
