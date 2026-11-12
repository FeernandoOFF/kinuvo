---
title: "Sub-agents"
description: "Server-owned child Sessions with durable results, shared limits and parent Approvals."
---

Status: Accepted

## Decision

`agents_spawn`, `agents_wait`, `agents_send` and `agents_list` are Server tools invoked through ToolService, including through KinuvoMCP. A Sub-agent runs in a Hidden Session with a typed parent Session and starting tool-call ID. Each update carries its child Session ID and current call ID; clients key children by Session ID, never batch order.

Hidden Sessions use on-device models, pin their settings, have no external plugin, Memory or egress tools, skip Reflection and send no Notifications. They are absent from normal Session listings and broadcasts. The parent supplies the child's task and context explicitly. Returning a Hidden Session's answer to a normal parent Agent requires an Approval showing that result; withholding it returns no private content to the model.

Child states and results are persisted as structured Session notices in the parent's log. `SUB_AGENT_CHANGED` delivers live updates; catch-up reads the persisted states. Waits are repeatable and bounded to 30 minutes. After restart an unfinished child is reported cancelled; completed results remain available. A follow-up queues behind a running child.

Pending interactions are relayed through the parent's existing pipeline with the originating child Session ID. Answers on the parent route back to the child. Cancellation stops descendants recursively and records a cancelled result. Cancellation exceptions propagate after cleanup.

The root run and its descendants share an Activation budget of 64 tool executions. A root run resets it; child turns and follow-ups do not. Exhaustion cancels the tree. Nesting is limited to two child levels. Model calls remain bounded by each harness's existing step limit.

## Consequences

External Agents can start and wait for on-device Sub-agents through KinuvoMCP under the same policy and Approvals. External harnesses cannot execute Hidden Sessions. Hidden Sessions deliberately have a narrower tool inventory. Restart resumes observation of durable outcomes, not interrupted execution.
