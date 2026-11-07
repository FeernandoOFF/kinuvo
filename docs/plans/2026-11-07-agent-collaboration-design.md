---
title: "Agent collaboration"
---

Lets Agents work with one another through Kinuvo's own tools. Decision: [ADR 0023](../adr/0023-agent-tools-over-mcp.md).

## Design

- **One service, many transports.** Every call carries a caller scope: Agent ID, Session ID, delegation depth. Native Agents get the tools as built-in tools; External Agents reach them over MCP at the Server's `/mcp`, with a token scoped to one Session. ACP `session/new` receives the MCP server: HTTP when advertised, else the hidden `kinuvo mcp-bridge` stdio command.
- **Kinuvo's rules apply.** Each call goes through Risk, Approval, Trust and Deny rules, and the Approval appears in the caller's Session.
- **Scope.** Session tools see only the caller Agent's own Sessions (from PR 2, plus the children it delegated). Never another Agent's.
- **Hidden Sessions.** A Hidden Session is excluded from every Agent-facing list, search and read, for all callers; the user still sees it in the app. Purpose: sensitive data processed by an on-device model, with only a final result crossing to cloud Agents.
- **Remote execution grants** do not get these tools.

## Roadmap

1. **PR 1, read tools (this change).** `agent_tools` group: `agents_list`, `sessions_list`, `sessions_search`, `sessions_read`, `session_status`, all Low risk. Native tools and the MCP endpoint with ACP injection. The `hidden` flag on Sessions lands, with exclusion enforced in the tools.
2. **PR 2, delegation.**
   - `delegate` runs a goal in another Agent, native or External, through the shared `ExecutionDispatch`; single or batch, foreground or background, with `delegate_wait`.
   - `subagents` lists and cancels children. Stopping a parent cascades to its children. A depth limit bounds chains. A child Session links to its parent.
   - `hidden: true` gives the child only the goal and context, and the parent only the final result. A hidden child must run on an on-device model provider, and is excluded from the parent's Reflection and Memory. An optional review-before-handover Approval lets the user see the result first.
3. **PR 3, management tools.** `agent_update`, `agent_create` (blank), `mcp_add`, `mcp_remove`, `mcp_test`, `mcp_login`, `session_configure`. All High risk, with a diff shown in the Approval. An Agent can never raise its own Risk tolerance or add Trust rules.
4. **Later.** Board handoffs between Agents (undecided) and Teams.
