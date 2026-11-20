---
title: "Sub-agents"
description: "Child Sessions using any model, with opt-in private on-device execution."
---

Status: Accepted

## Decision

The Server tools `agents_spawn`, `agents_wait`, `agents_send` and `agents_list` manage child Sessions. Regular Sub-agents use the target Agent's configured provider, model, reasoning, approvals and tools, including plugins. Results return directly to the parent. Child identity is its Session ID, with a typed parent link and current tool-call ID.

The optional `private` flag creates a Hidden Session: an on-device model is required, settings are pinned, and tools are restricted to Sub-agent tools, Session configuration and asking the user. Sharing its result with a non-hidden parent requires Approval. After delivery, including withholding, its transcript is deleted and the delivered result remains in the parent's notices. Deleted children reject follow-ups.

Children of Hidden Sessions remain private. All child Sessions are excluded from chat listings, Reflection and Notifications. Deleting a parent deletes its descendants. Regular children retain their transcripts and support follow-ups.

Interactions relay through the parent. Cancellation stops descendants. The root and children share an Activation budget of 64 tool executions and nesting is limited to two child levels. Waits are repeatable and bounded to 30 minutes; unfinished children are reported cancelled after restart.

## Consequences

Sub-agents work with cloud and local models by default. Privacy is explicit and restricts execution and result sharing. Durable parent notices retain outcomes; restarting does not resume interrupted execution.
