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
