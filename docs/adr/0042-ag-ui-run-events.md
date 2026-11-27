---
title: "AG-UI run events"
description: "The Server streams each run as AG-UI 1.0 events inside a Kinuvo Session subscription; everything else stays Kinuvo RPC."
---

# AG-UI run events

## Context

`AgentEvent` already borrows AG-UI names but adds fields AG-UI does not define, emits events between runs, and keeps Approvals outside the run lifecycle. AG-UI 1.0 now has a written spec with interrupts, activity messages and typed run outcomes.

## Decision

### Scope

- The Server emits AG-UI 1.0 run events. Input stays `chat.send` (Turn, Queue, Steer per ADR 0039) and `session.respond`. The Server does not accept `RunAgentInput`, direct `resume`, `forwardedProps` or AG-UI shared state; those wait for an HTTP+SSE gateway.
- Everything outside a run (Agents, Devices, MCP, settings, the inbox from ADR 0041) stays Kinuvo RPC. There is no AG-UI "extension" layer.
- Kinuvo-only data goes in `metadata["kinuvo"]`, sent whole on every event of an item because metadata merges per key. Custom names and interrupt reasons use the `kinuvo:` prefix. No `ag-ui` metadata keys, `MESSAGES_SNAPSHOT`, `STATE_*`, `STEP_*`, `REASONING_ENCRYPTED_VALUE` or `AgentCapabilities`.

### Session subscription

- Subscribing returns a snapshot: a sequence number, a page of persisted history up to that number (entries committed during the active run included; a page too large for one frame puts its first entries in the snapshot and the rest follow in a few bounded `continued` frames before anything live), Queued messages, children with their pending interactions, pending interactions (including ones outside runs, such as MCP App calls and Workflow steps), and the active run if any (a run too large for one frame puts its first events in the snapshot and the rest follow in `continued` frames after the entries, before anything live).
- Each following frame is either one AG-UI event of the active run or a Kinuvo item: history committed, queue or children changed, interaction requested, staged, unstaged or resolved, wake, Reflection, voice call record, notice, or a run-less status (Idle, Lost).
- Catch-up during a run replays that run's buffered events from `RUN_STARTED`. Snapshot and replay share one cutoff, and the client reducer skips entries it already holds by id. The reducer in `client/app` covers only the active run and is cleared as entries commit.

### Runs

- A run starts with `RUN_STARTED` (`protocolVersion: "1.0"`) and ends with `RUN_FINISHED` or `RUN_ERROR` after every text, reasoning and tool call it opened is closed. Per-run usage is future work; usage stays on Kinuvo RPC.
- `ExecutionPhase` maps to runs: Running is an active run, RequiresAction is `RUN_FINISHED` with an interrupt outcome, Lost is `RUN_ERROR`. Idle and Lost outside a run are Kinuvo items.
- Stop on an active run closes open items and ends it with the cancelled outcome. Stop on a parked run resolves its interactions and cancelled tool results through Kinuvo items, then reports Idle.
- Compaction, warnings and live tool output are activity messages (`kinuvo:compaction`, `kinuvo:warning`, `kinuvo:tool-progress`); live output is a `progress` tool result and does not close the call, only the final result does. A script's nested calls are tool calls of the run, tagged with their root call, and stay with its interrupt. Future work: tool reports, agent-finished cards, notices and Surfaces (`a2ui-surface`, `kinuvo:html-surface`, `mcp-apps`) as activity messages; they are still Kinuvo items and entries. Surface actions stay on Kinuvo RPC, and the A2UI version string stays `"v1.0"`.
- Device tools and Computers stay Server-owned tools (ADR 0030); catalogs are chosen as in ADR 0021.
- Sub-agents are separate Sessions whose runs carry `parentRunId`, not AG-UI `SUBAGENT_*` events, because a child outlives the parent's run.

### Interactions as interrupts

- When an interaction is pending, the Server parks the execution (built-in loop, `run_code` script, ACP request). Parking does not end the execution internally: no `RunFinished`, `onDone` or finish listener fires, so Teams, Tasks, Sub-agents, Notifications and the Session log are unchanged. Only the wire translator closes the AG-UI run with the interrupt outcome, once no sibling work is still running, and freezes the interrupt ids.
- Answers arrive only through `session.respond` and `teams.respond`, with the current management-access checks. The Server stages each answer, shows it to every Device, and continues once every interrupt is covered. One atomic claim settles answers, late sibling interactions, timeouts and Stop (Stop on an active run blocks the interactions open at that moment from being answered or released; with no run, interactions such as Workflow Approvals stay answerable); an answer of the wrong kind is refused before it is staged, a claimed batch is released on the Server's own scope so a departing responder can't interrupt it, an answer whose release fails is unstaged so Devices can answer again, and the batch is released outside the lock of the work that resolved the last sibling; the winner opens the new run (same Session, new `runId`) before releasing work. Routine timeouts, Device reconnects, Computer takeover handback and private-child delivery settle through the same claim. If every answer arrives before the run closes, it continues without an interrupt outcome.
- After the run closes, late external-harness output is buffered until the new `RUN_STARTED`, and a new interaction waits in the Session subscription.
- Mapping: Approval is `tool_call` with `toolCallId`, answered with `allow_once`, `allow_session`, `always_allow` or `deny` plus an optional note; its risk, trust hint, identity and preview go in `metadata.kinuvo`. Questions is `input_required` with a response schema. HarnessApproval is `kinuvo:harness_approval` with the option ids. OpenLink is `kinuvo:open_link`. Connect (MCP sign-in card) is `kinuvo:connect`. Unsupported is `kinuvo:unsupported` and can be cancelled.
- A Session with open interrupts is not idle: wakes and Queued messages wait, and `chat.send` queues. A child's interrupts belong to the child's run and appear on the parent through its children.
- Parked executions do not survive a Server restart. Their interrupts resolve as Lost, and continuing opens with `RUN_ERROR`.

## Consequences

- Run streams validate against AG-UI 1.0 `schema.json`, and a future HTTP+SSE gateway can serve them unchanged.
- A draft HTTP+SSE gateway lives in `gateways/ag-ui`.
- An approved tool now waits for its sibling Approvals before it runs.
- The change lands in steps that keep main green: event models, translator and reducer alongside the current wire; parking and staged answers on protocol version 8; then one cutover to version 9 (Current and Minimum) across the Server, clients, `harness/remote`, `gateways/acp-agent` and `gateways/kinuvo-mcp`.
