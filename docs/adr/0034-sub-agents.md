---
title: "Sub-agents"
description: "Child Sessions that report completion, with opt-in private on-device execution."
---

Status: Accepted

## Decision

The Server tools `agents_spawn`, `agents_wait`, `agents_send`, `agents_cancel` and `agents_list` manage child Sessions. Regular Sub-agents use the target Agent's configured harness and tools. Built-in and external Agents share the same turn completion and cancellation contract. A child is identified by its Session ID and the tool call that started its current turn.

Completion wakes the parent Session. The Server batches available results into a ToolReport and delivers it at the next built-in model request or starts a turn once the parent is idle. The report can inline up to 16 KiB of result text and points to `agents_wait` for larger results. The Server delivers each child turn once through a wake or an in-flight wait. A wait defaults to 30 seconds and caps at 45 seconds. Results remain readable after delivery. Use `agents_cancel` to stop work without a wake. `agents_send` queues a follow-up by default and can steer built-in turns at a safe point. Start a new child for an independent re-check.

A Hidden Session requires an on-device model, pinned settings and restricted tools. Its ToolReport carries status only. Sharing its result with a parent outside a Hidden Session requires Approval through `agents_wait`. The result stays in the child until Approval. After delivery or withholding, the transcript is deleted and the parent's durable notices retain the outcome. Legacy results still require Approval.

Interactions relay through the parent. Child Sessions stay out of regular chat listings, Reflection and Notifications. Cancellation and deletion extend to descendants. The root and its children share an Activation budget of 64 tool executions and nesting is limited to two child levels.

## Consequences

A parent can end its turn while children work. Durable notices preserve terminal results, and restarting the Server does not resume unfinished child turns.
