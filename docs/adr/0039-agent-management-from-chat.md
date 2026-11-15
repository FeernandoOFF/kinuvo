---
title: "Agent management from chat"
description: "Caller-scoped Server tools that let an Agent change its own setup under Approvals that show the change."
---

Status: Accepted

## Decision

The `agent_manage` Toolset holds `agent_get`, `agent_update`, `agent_create`, `mcp_list`, `mcp_add`, `mcp_remove`, `mcp_test`, `mcp_login` and `session_configure`. They are Server tools invoked through ToolService, so KinuvoMCP offers them under the same Tool scope and Approvals. They act only on the calling Agent and Session and reuse the Settings handlers: Admin, MCP management and `HarnessRuntime.configure`.

A tool's `preview(args)` builds the Approval's `ChangePreview` on the Server; Approvals shown outside Kinuvo use its text. A preview that fails refuses the call before anyone is asked, as do arguments a tool doesn't declare. An Approval with a preview carries no raw arguments. Environment and header values never appear in it.

`agent_update`, `agent_create`, `mcp_add` and `mcp_remove` mark their Action `alwaysAsk` and not trustable: they ask at every Risk tolerance and never save a Trust rule. `session_configure` and `mcp_login` follow the Risk tolerance.

Chat never loosens safety. Agent Risk tolerance, Trust and Deny rules, Exec backend, Workspace and tool groups the Server lacks are not parameters; naming them fails with a pointer to Settings. A Session's Risk tolerance may only be lowered. `agent_create` makes a blank Agent from defaults, never a clone. MCP changes touch only the Agent's own servers. `mcp_login` shows its sign-in link through the `ask_user` form and completes with the address the user pastes back.

New Agents get the Toolset by default; Agents with an explicit `tools:` list opt in. The tools sit in the Interactive group, so Micro and Small Model profiles leave them out. A Hidden Session gets at most `session_configure`, which keeps ADR 0034's pinned settings.

## Consequences

External Agents get none of these tools: their identity and configuration live in their execution registration, not the Agent store. Kinuvo-side identity tools for them, a Settings toggle and richer diff card for the Toolset, and capturing the MCP OAuth callback on the Server are follow-ups.
