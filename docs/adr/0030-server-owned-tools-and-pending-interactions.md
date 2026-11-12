---
title: "Server-owned tools, Approvals and pending interactions"
description: "One tool policy path and one pending-interaction pipeline for built-in, external and MCP callers."
---

Status: Accepted

Supersedes [ADR 0016](0016-external-agent-execution.md) in part: the executing harness no longer owns tools and tool policy for Kinuvo tools.

## Context

Tool policy and Approvals live inside the built-in harness, so external harnesses cannot use Kinuvo tools under the same rules. Native runs and external runs also publish and answer pending interactions through different events and RPCs.

## Decision

**One tool invocation service.** The Server runs every tool call from the built-in harness (through `ToolProvider`/`ToolGate`), KinuvoMCP and MCP Apps through one path: tool identity, Tool scope, Deny and Trust rules, redaction, result limits, audit and nested calls such as `run_code`. Every built-in tool, including Memory, Skills, `show_ui` and `show_html`, is a Server tool. The Server builds Instructions and passes them to the harness; the harness runs the turns.

**One pending-interaction pipeline.** Approvals, `ask_user` questions and external harness requests share one event and one answer RPC per Session, replacing `Approve`, `Answer` and `ExecutionAnswer`. A KinuvoMCP call awaiting an Approval appears on its owning Session.

**KinuvoMCP over HTTP.** Credentials are per run, renewed at each run boundary for retained ACP Sessions, revoked on cancel or end, and stale calls are rejected. The Kinuvo Approval is authoritative for Kinuvo tools: a recognised KinuvoMCP call in an ACP permission request is auto-allowed; otherwise the request is relayed like other external requests. An external harness's private tools stay under its own policy.

**Remote Agents** keep the source Server's policy and Approvals, as in ADR 0016.

**Embedded Server** runs the built-in and remote harnesses only: no ACP, no KinuvoMCP.

## Consequences

External Agents can use Memory, Skills and other Kinuvo tools through KinuvoMCP under the user's Approvals. The pending-interaction change is a breaking protocol change and lands with client updates. Harnesses that cannot renew credentials are not offered KinuvoMCP.
