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
