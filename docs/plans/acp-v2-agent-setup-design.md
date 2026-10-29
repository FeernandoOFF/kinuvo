---
title: "ACP v2: Agent setup and inspection (design handoff)"
---

Status: implemented in shared/app + shared/ui against the backend contract in [acp-v2-backend-handoff.md](acp-v2-backend-handoff.md); see Implementation below. Field names in later sections are the original proposals; the shipped contract is the handoff's. Mockup: [acp-v2-agent-setup-mockup.html](acp-v2-agent-setup-mockup.html).

## Goal

The selected Server registers Agents that run through one of three **Harness connections**, then shows them in the same rail, inspector and Session screen as today's Agents:

| Kind | Runs where | Who owns instructions, Memory, Skills, tools | Kinuvo owns |
|---|---|---|---|
| Kinuvo (built-in) | The selected Server | Kinuvo (unchanged) | Everything (unchanged) |
| ACP command | The selected Server's machine; computer Servers only | The external harness (Claude Code, Codex, Gemini CLI…) | Display name, avatar, working folder, advertised controls, Approval routing |
| Remote Kinuvo | Another Kinuvo Server | The remote Server | Display name, avatar, Approval routing |

Out of scope: downloading/installing harnesses, signing into accounts on the user's behalf, teams/delegation, broad onboarding redesign. First-run onboarding still creates a built-in Agent.

## Terms (propose for CONTEXT.md)

- **Harness connection**: a way this Server runs Agents, configured once and reused by several Agents: Kinuvo itself, an ACP command on this machine, or another Kinuvo Server. _Avoid_: runtime, backend, provider (provider = model provider).
- **External Agent**: an Agent whose harness connection isn't Kinuvo; its instructions, Memory, Skills and tools belong to that harness.
- **Exposed Agent**: an Agent a Server lets linked Servers use.

UI copy uses "runs on" + the real Server name, never "this device" unless the Server is Embedded on the device showing it.

## Execution-location labels

`host` = name of the machine that executes (built-in/ACP: `ConnectResult.serverName`; remote: remote Server's name as reported at link time, overridable alias).

| Case | Inspector "Runs on" | Rail/card subtitle |
|---|---|---|
| Built-in, remote Server | `Kinuvo on Mac mini` | none (unchanged) |
| Built-in, Embedded | `Kinuvo on this iPhone` | none |
| ACP | `Claude Code on Mac mini` | `Claude Code · Mac mini` |
| Remote | `Ada on Studio` (remote name if different) | `Studio` |

Phone connected to a Mac Server sees "Mac mini", never "this phone". The setup question is phrased "Where does it run?" and each option names the host.

## Readiness

Every Harness connection and every Agent carries the existing `CapabilityState` (support/availability/authorization + `reason` + `reasonCode`). UI maps `reasonCode`:

| reasonCode | Pill | Message (template) | Action |
|---|---|---|---|
| — (actionable) | Ready (success) | — | — |
| `checking` | Checking… | — | — |
| `not_found` | Not found (destructive) | `No "{command}" on {host}. Install it there, then check again.` | Check again, Edit |
| `needs_sign_in` | Needs sign-in (warning) | `{harness} on {host} isn't signed in. {authMethod.description}` | Sign in (only if harness offers a non-terminal auth method), Check again |
| `host_offline` | Offline (muted) | `{host} is offline. {name} can't reply until it's back.` | Check again |
| `unpaired` | Unlinked (destructive) | `{host} no longer accepts this Server. Link it again.` | Link again |
| `agent_gone` | Removed (destructive) | `{host} no longer shares {remoteName}.` | Remove Agent |
| `incompatible` | Update needed (warning) | `{harness} {version} speaks ACP v{n}; Kinuvo needs v{m}.` | Check again |
| `unsupported` | Not on this Server | `ACP commands need a computer Server. {server} is an {platform}.` | — |
| other | Error | `reason` verbatim | Check again |

Shown in: Settings › Harnesses list rows, setup step option rows, inspector Status row, a rail status dot (non-Ready only), and a banner above the composer (composer disabled from `SessionCapabilities.chat`, which already carries `CapabilityState`).

## Settings › Harnesses (new Server section)

`SettingsSection.Harnesses("Harnesses", server = true)` between Model providers and Tools & MCP. Hidden unless the Server advertises the integration.

List: one row per connection: icon, name, `{kind} · {host}`, `{n} Agents`, status pill. Built-in "Kinuvo" row first, not editable. Buttons: **Add ACP command**, **Link a Kinuvo Server** (each disabled with the `unsupported` reason when not offered).

**ACP command form**
- Name (required, ≤40, unique among connections; prefilled from probe's `agentInfo.title` after first check).
- Command (required; bare name resolved on host PATH or absolute path). Hint: `Runs on {host}. Use an ACP adapter already installed there, e.g. claude-code-acp.`
- Arguments (optional, one line, shell-split by the Server).
- Environment (optional key/value rows; values may be Secret references `${…}`; hint links Secrets).
- **Check** runs the probe → shows `{title} {version}`, status pill, offered auth methods, advertised controls (read-only list: "Model, Mode").
- Save: enabled when name+command valid and last probe returned anything except `not_found`/`incompatible` (sign-in may be finished later). Save re-probes.
- Remove: disabled while Agents use it; text `Used by Claude, Reviewer. Remove those Agents first.`

**Kinuvo Server link form**
- Address (required; `http(s)://` or `ws(s)://`; reuse existing pairing URL parsing, accept a Pairing link).
- Pairing code (required; from the other Server's Pair a Device). Hint: `On {other}, open Settings › Connection › Pair a Device and allow linking Servers.`
- Alias (optional; defaults to reported Server name).
- **Link** → success shows the remote Server name and its exposed Agents count. Errors: `That's this Server.` (same serverId), `Code expired or wrong`, `Can't reach {address}`, `{name} doesn't share any Agents yet. Turn on "Available to linked Servers" in an Agent's settings there.`

## New Agent flow (extend NewAgentScreen)

Today: `AgentSteps = [Identity(4), Soul(5)]`. Add one leading step and branch; the visual frame (OnboardingContent, avatar stage, single primary action) stays.

0. **Where does it run?** (new `HarnessStep`) Headline: `Where does your new Agent run?` Three groups of selectable rows:
   - `Kinuvo on {server}` — "Kinuvo's own Agent. You set its Soul, Memory, Skills and tools." (default selected)
   - `ACP agent on {server}` — then one row per existing ACP connection with pill; last row `Add an ACP command…` (opens form inline as a sub-step). Group disabled with `unsupported` reason on phone/Embedded Servers.
   - `Agents from another Kinuvo Server` — one row per linked Server with pill; `Link a Kinuvo Server…`.
   - Rows whose connection isn't Ready stay selectable (user may fix later) except `not_found`/`unsupported`/`unpaired`.
   - Action: Continue.
1. **Branch Kinuvo**: existing Identity → Soul → Create. Unchanged.
2. **Branch ACP**: `Identity` step (name + avatar; Personality text hidden) with an added **Working folder** field (required, absolute path on host; hint `On {host}. {harness} reads and edits files here.`) and a read-only line `Instructions, Memory, Skills and tools come from {harness}.` Action: `Meet {name}` → create.
3. **Branch Remote**: **Pick Agents** step: headline `Which of {host}'s Agents?` Checkbox list of exposed Agents (avatar, remote name, status pill); already-added ones checked+disabled with `Added`. Action `Add {n} Agent(s)` disabled at 0. Names collide → auto `"{name} ({host})"`, editable later in the inspector. No Identity/Soul step. After add, open the first added Agent.

Validation per step: existing `AgentDraft.problems` name rules for any name; working folder non-blank + absolute (server confirms existence, error `{path} isn't a folder on {host}`); remote selection ≥1.

## Inspector (AgentInspector) by kind

Built-in: unchanged, plus one row at the top of a new **Runs on** group: `Kinuvo on {server}` (no action) and, if the Server supports exposure, toggle `Available to linked Servers`.

External Agents replace the body below the avatar:

- **Identity**: Name (local), avatar picker. No Personality field.
- **Runs on**: NavRow `{harness} on {host}` → opens Settings › Harnesses at that connection. Status row: pill + message + action from Readiness table. ACP only: `Working folder` input. Remote only: `Remote Agent: {remoteName}` read-only.
- **Controls** (only if `details.controls` non-empty): one row per advertised control, `Picker` for select kinds (e.g. Model, Mode, Reasoning). Labels and options come from the harness verbatim; unknown kinds are omitted, never faked. Empty → group hidden.
- **Approvals**: text `{harness} asks before acting; answer from any Device with access.` No Risk tolerance / Trust rules rows (not applied to external harnesses in v1).
- **Managed by {harness}**: muted text `Instructions, Memory, Skills and tools are set in {harness} on {host}.` Hidden rows: Soul, User Notes, Skills, Routines, Workspace, MCP, Tools and Trust rules.
- Footer, dirty guard and Save unchanged; `AgentDraft` gains `cwd` and `controls`.

`AgentContent`/Overview tiles: for external Agents show only Personality(=identity) and the Runs-on summary; hide tiles for harness-owned sections.

## Session screen

Same `ChatScreen` for every kind.
- Composer "This Session only" panel shows only `SessionCapabilities.controls`; model picker/custom model/Risk tolerance hidden when not advertised. "Edit {name}'s defaults" link stays.
- Banner above composer when `chat` isn't actionable, using Readiness copy and action; send disabled; typed text kept.
- Approval card: when `ApprovalRequested.options` present, render one button per option using harness labels, mapping `allow_once`→primary, `allow_always`→outlined, `reject_*`→ghost destructive. No Deny note unless offered. Absent → today's three buttons.
- Stop maps to harness cancel; `RunError` shows `{harness} stopped: {reason}`.
- Questions/Approvals appear on every connected Device authorized for the Agent and Notify as today; first answer wins, others see `Answered on {device}`.

## Proposed protocol needs (for backend)

```kotlin
enum class HarnessKind { Kinuvo, Acp, Remote }
data class HarnessConnection(
    val id: String, val kind: HarnessKind, val name: String,
    val host: String,                      // execution host display name
    val state: CapabilityState,
    val agents: List<String> = emptyList(),// Agent IDs using it
    val acp: AcpSettings? = null, val remote: RemoteLink? = null,
    val probe: HarnessProbe? = null,
)
data class AcpSettings(val command: String, val args: List<String> = emptyList(), val env: Map<String, String> = emptyMap())
data class RemoteLink(val url: String, val serverId: String, val alias: String? = null)
data class HarnessProbe(val state: CapabilityState, val title: String? = null, val version: String? = null,
    val protocolVersion: Int? = null, val authMethods: List<AuthMethod> = emptyList(), val controls: List<HarnessControl> = emptyList())
data class AuthMethod(val id: String, val name: String, val description: String? = null, val interactive: Boolean)
data class HarnessControl(val id: String, val label: String, val kind: String /* select */, val options: List<ControlOption>, val value: String?)
data class ControlOption(val id: String, val label: String, val description: String? = null)
data class ExposedAgent(val id: String, val name: String, val avatar: String?, val state: CapabilityState, val addedAs: String? = null)
```

- Capabilities: `Integration.Harness` with options `acp`, `remote` (built-in implied); absent → section hidden, Unsupported reasons via `CapabilityState.unsupported`.
- Methods: `Harnesses` → `List<HarnessConnection>`; `SaveHarness(id?, kind, name, acp?)`; `LinkServer(url, pairingCode, alias?)`; `CheckHarness(id | draft)` → `HarnessProbe`; `AuthenticateHarness(id, methodId)`; `RemoveHarness(id)` (error if in use); `ExposedAgents(connectionId)`.
- Event: `HarnessesChanged` (list), and Agent state changes via existing Agents list refresh.
- `AgentInfo` + `harness: String?` (connection id, null = Kinuvo), `host: String?`, `state: CapabilityState = CapabilityState()`.
- `AgentDetails` + `cwd: String?`, `remoteAgentId: String?`, `controls: List<HarnessControl>`, `managed: Boolean` (harness owns instructions/Memory/Skills/tools); `exposed: Boolean` for built-in.
- `CreateAgentParams` + `harness`, `cwd`; new `AddRemoteAgents(connectionId, agentIds)` → created `AgentInfo`s.
- `UpdateAgentParams` + `cwd`, `controls: Map<String, String>`, `exposed`, `avatar` (exists).
- `SessionCapabilities` + `controls: List<HarnessControl>`; `chat` carries readiness reasonCodes above. `SessionSettings` + `controls`.
- `ApprovalRequested` + `options: List<ApprovalOption>?` (`id`, `label`, `kind`); `ApprovalParams` + `optionId`. Server-to-server forwarding of Approvals/Questions for Remote Agents; answer from any authorized Device; resolved event names the answering Device.
- Pairing: allow a "linked Server" pairing that grants access to exposed Agents only.

### Reconciliation with backend draft (`shared/protocol/src/Execution.kt`, uncommitted)

Same term (`HarnessConnection`), `ExecutionKind.Native` = Kinuvo built-in. Gaps the UI needs:

| Backend draft | UI needs |
|---|---|
| `Acp(command, cwd)` on the connection | `cwd` per Agent (two Agents, one Claude Code, different folders). Either move `cwd` to `AgentRegistration` or accept one connection per folder; UI copy assumes per Agent. |
| `Remote(serverId, agentId)` on the connection | One connection per linked Server; `agentId` on `AgentRegistration` (`remoteAgentId`). Otherwise "link once, pick several Agents" becomes N connections. |
| `ExecutionReadiness(configured, online, reason)` | A `reasonCode` (`not_found`, `needs_sign_in`, `host_offline`, `unpaired`, `agent_gone`, `incompatible`, `unsupported`) or reuse `CapabilityState`; free text alone can't pick pill/action. |
| no host name | `host` display name per connection (remote Server's name for Remote). |
| `ExecutionCapabilities(resume, cancel, questions, approvals)` | Add `controls: List<HarnessControl>`, probe `title`/`version`/`authMethods`. UI hides Stop when `cancel=false`, hides Approval routing copy when `approvals=false`. |
| no check/probe method | `execution.connection.check` (draft or id) before save. |
| no exposed-Agents listing | `execution.remote.agents(connectionId)` + exposure flag on the remote. |
| `ExecutionRequest.payload: JsonElement` | Typed Approval options (`id`, `label`, `kind`) so the card can render harness labels; Question payload = existing `Question` list. |
| `ExecutionCatalog` has agents separately from `AgentInfo` | Either `AgentInfo` gains `connectionId`/`host`/`state`, or the client joins `ExecutionCatalog.agents` to `Agents` by id. Prefer the former: rail, inspector and Session already read `AgentInfo`. |
| no capability gate | `Integration.Harness` options in `ServerCapabilities` so phone/Embedded Servers report ACP unsupported. |

## Shared state (shared/app)

- `HarnessRepository` (agent/): `connections: StateFlow<List<HarnessConnection>?>`, load/save/link/check/authenticate/remove, listens to `HarnessesChanged`; attached like `ConfigRepository`.
- `HarnessForm` (agent/, like `ServiceFormState`): `kind, name, command, args, env, url, code, alias, probe, checking, problems()`.
- `OnboardingState` + `harness: String?` (selected connection id, null = Kinuvo), `form: HarnessForm?`, `cwd`, `exposed: List<ExposedAgent>`, `picked: Set<String>`; `AgentSteps` becomes `[HarnessStep, 4, 5]`, with `steps` recomputed on choice (`Acp → [HarnessStep, 4]`, `Remote → [HarnessStep, PickStep]`). `create()` branches.
- `SettingsViewModel`: Harnesses section state (list + open form) via `HarnessRepository`.
- `AgentDraft` + `cwd`, `controls`; `changes()` / `problems()` extended; `AgentInspectorViewModel` unchanged otherwise.
- `SessionViewModel`: pass `options` through; `approve(optionId)`.
- Tests in `shared/app/test`: step routing per kind, validation, draft changes for controls/cwd, label helper `executionLabel(agent, server)`.

## Files expected to change

shared/protocol: `Capabilities.kt`, `Config.kt`, `Api.kt`, `AgentEvent.kt`, `Approval.kt`, new `Harness.kt`.
shared/client: method registrations only if not generic.
shared/app: new `agent/HarnessRepository.kt`, `agent/HarnessForm.kt`; `agent/OnboardingViewModel.kt`, `agent/AgentInspectorViewModel.kt`, `agent/SettingsViewModel.kt`, `navigation/Route.kt`, `session/SessionViewModel.kt`, Koin wiring; tests.
shared/ui: new `agent/HarnessSettings.kt`; `agent/OnboardingScreen.kt` (HarnessStep, PickStep, cwd field), `agent/AgentInspector.kt`, `agent/AgentOverview.kt`, `agent/SettingsScreen.kt`, `session/SessionSettingsRow.kt`, `session/ChatScreen.kt` (banner, approval options), `navigation/Shell.kt` (rail status dot/subtitle); screenshot tests in `test@jvm/ScreenshotTest.kt`, `OnboardingScreenshotTest.kt`.
apps/ios: none (Settings, onboarding and Session are Compose-hosted; native Board unaffected).
docs: `CONTEXT.md` terms; an ADR for harness ownership split.

## Acceptance criteria

1. On a macOS/Linux/Windows Server, user adds an ACP command once, sees Ready or a specific readiness reason with the host's name, and creates two Agents that reuse it.
2. On a phone/Embedded Server the ACP option is visible but disabled with the `unsupported` reason; Kinuvo and Remote remain.
3. From a phone connected to "Mac mini", every label reads "Mac mini", never "this device".
4. Linking another Server lists only its exposed Agents; user must check each one; nothing is added implicitly; already-added ones show `Added`.
5. External Agent inspector shows name, avatar, Runs on, status, working folder (ACP) and only advertised controls; Soul/Skills/MCP/Routines/Workspace/Trust rows absent.
6. Changing an advertised control saves through the existing dirty guard and applies to new Sessions.
7. When a harness is signed out/offline/removed, rail dot, inspector status and composer banner show the same reason and action; sending is disabled without losing typed text.
8. A harness permission request appears as an Approval on every authorized Device with the harness's option labels; answering on one updates the others.
9. Built-in Agents and first-run onboarding behave exactly as before (existing screenshot tests unchanged except added rows).
10. No UI downloads or installs anything, and no UI shows a teams/delegation entry.

## Unresolved questions

1. Exposure granularity: per-Agent toggle on the remote (proposed) or per linked Server?
2. Routines for external Agents: hidden in v1 (proposed) or allowed as plain prompts?
3. Risk tolerance for ACP: always ask (proposed) or let `full` auto-pick `allow_once`?
4. Does ACP probe need a running session to list controls (models/modes), or will backend cache from last session?
5. Can a remote Agent's controls (model/reasoning) be changed from the linking Server, or read-only?
6. Linked-Server pairing: reuse Device pairing code with a scope, or a separate code type?

## Implementation (UI, 2026-10-28)

Built against the typed `Api.Execution*`, `SaveRemoteLink`, `GrantExecution`/`RevokeExecution`/`ExecutionGrants` and `DiscoverRemote` methods. No protocol or backend edits.

### Files

shared/app
- `agent/Harness.kt` (new): `external`, `harnessName`, `runsOn`, `railSubtitle`, `serverLabel`; `ExecutionReadiness.state()` and `readiness()`, which map reason codes to a pill, a message naming the host, and actions; `AcpForm` (literal argv, one argument per row, no shell splitting; names unique; Save needs a usable check of the typed argv); `LinkForm` (wss/localhost only); `ShareForm`, `shareable`; `HarnessChoice` and `agentSteps`; `selectable`, `remoteName`, `approvalOptionStyle`, `cwdProblem`.
- `agent/HarnessRepository.kt` (new; on `ConnectedServer.harnesses`): catalog and `ExecutionCatalogChanged`, probe/check, save/delete/register, link (private token, then discover, then save the connection), discover, share/revoke/grants.
- `session/External.kt` (new): `ExecutionRequest.prompt()` converts each request into one of three prompts:
  - ACP permission → Approval with typed options.
  - ACP form elicitation → Questions; answers are encoded in the schema's own types.
  - A linked Kinuvo Server's native `QuestionsAsked` → Questions, answered as `QuestionsParams` (null skips).
  - URL elicitation → Unsupported, which can only be declined.
- Changed:
  - `OnboardingViewModel`: `HarnessStep`/`PickStep`, the harness choice, `cwd`, shared/picked Agents, `discover()`, and `create()` branching per kind.
  - `AgentInspectorViewModel`: `AgentDraft.cwd/kind/registration()`; an external save goes through `RegisterExternalAgent`; `check()`.
  - `SettingsViewModel`: Harnesses state, ACP/link/share forms, grants.
  - `SessionViewModel`: external snapshot via `ExecutionSnapshot`/`ExecutionChanged`, `answerExternal`, `checkHarness`; `ChatState.prompts/harness/external`.
  - `Route`: `SettingsSection.Harnesses`, gated on `Integration.Harness`.
  - `ConnectedServer`.

shared/ui
- `agent/HarnessSettings.kt` (new): Harnesses list, detail, ACP form with check/probe lines (version, sign-in methods as read-only instructions), Link form, Share form with one-time code display and persisted grants with Delete.
- `session/ExternalPrompts.kt` (new): readiness banner with Check again (send disabled, typed text kept), Approval card with harness labels and styles, Questions via the existing `QuestionsCard` (Decline for ACP, Skip for Kinuvo forms).
- Changed:
  - `OnboardingScreen`: the "Where does it run?" step, ACP working folder, remote pick step, and the add/link overlay. First-run onboarding is unchanged.
  - `AgentInspector`: external body.
  - `AgentOverview`: Runs on and Managed by cards.
  - `SettingsScreen`: section wiring; external Agents get no nested sections.
  - `Shell`: a rail dot when not online.
  - `ChatScreen`: model and Risk tolerance controls hidden for external Agents.
  - `QuestionsCard`: lambda overload.
  - `Koin`.

Tests:
- `shared/app/test/HarnessTest.kt`: 11 tests covering step routing, argv, form/link/share validation, readiness copy, labels, the external draft, approval answers, form answers, and native Questions answer/skip.
- `shared/ui/test@jvm/HarnessScreenshotTest.kt`: renders `shared/ui/build/screens/harness-*.png`.

### Rules kept
- The new-Agent harness step follows the Server's `Integration.Harness` capability (`runsHarnesses`), even when it arrives late; harness rows show a spinner until the catalog loads.
- Controls absent: there's no controls contract, so there are no Controls rows and no probing turns.
- External Agents hide Routines, Soul, Memory, Skills, MCP, Tools, Workspace, Risk tolerance and Trust rules.
- No automatic approvals.
- Sign-in is an instruction to sign in with the harness on its host, then Check again. No login action.
- Labels name the real host. "this device" appears only when the Server is embedded here.
- Remote Agents are picked explicitly; already-added ones show "Added"; name collisions get " (host)".
- A link code grants only the Agents it names; the UI says it can't change the other Server's settings.

### Remaining gaps
- Settings › Harnesses › Remote detail doesn't list shared Agents. Add them through New Agent.
- No alias editing after linking.
- No link-code re-entry for `access_revoked` other than removing the connection and linking again.
- Remote readiness relies on backend probe/poll codes (`host_offline`, `access_revoked`).
- The external transcript uses the normal Session entries. Rich ACP tool, plan and terminal rendering isn't done.
- Stop is the native Stop; there's no `cancel` capability gate in the UI.
- Catalog and readiness need management access, so chat-only Devices show "ACP agent" as the harness name.
- iOS/Android device runs and Maestro flows are not done. There is no `.maestro` coverage for harness setup yet.
