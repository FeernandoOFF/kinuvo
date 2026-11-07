---
title: "Agent collaboration"
---

Lets Agents work with one another through Kinuvo's own tools. Decision: [ADR 0023](../adr/0023-agent-tools-over-mcp.md).

## Design

- **One service, many transports.** Every call carries a caller scope: Agent ID, Session ID, delegation depth. Native Agents get the tools as built-in tools; External Agents reach them over MCP at the Server's `/mcp`, with a token scoped to one Session. ACP `session/new` receives the MCP server: HTTP when advertised, else the hidden `kinuvo mcp-bridge` stdio command.
- **Kinuvo's rules apply.** Each call goes through Risk, Approval, Trust and Deny rules, and the Approval appears in the caller's Session.
- **Scope.** Session tools see only the caller Agent's own Sessions plus the children it delegated. Never another Agent's.
- **Hidden Sessions.** A Hidden Session is excluded from every Agent-facing list, search and read, for all callers; the user still sees it in the app. Purpose: sensitive data processed by an on-device model, with only a final result crossing to cloud Agents.
- **Remote execution grants** do not get these tools.
- **Loopback only.** `/mcp` answers peers on this machine; browsers, other hosts and proxied requests get 403.

## Delegation (PR 2)

- **Tools.** `delegate` (Medium) takes `agent` (default: the caller's own), `goal`, optional `context`, `model`, `hidden`, `background` and `review`, or `tasks: [{agent, goal, context, model, hidden}]` for a parallel batch of at most four. `delegate_wait` (Low) waits, with a timeout, for all or any of the caller Session's background children and hands each answer over once. `subagents` (Low; cancel is Medium) lists the caller Session's children (id, Agent, goal, state, hidden) and cancels one. They are in the `agent_tools` group, so native and MCP callers get them alike, under the same Risk, Approval, Trust and Deny rules.
- **Child Session.** Created on the target through the shared `ExecutionDispatch`, native or External. Its Details entry records the parent (Agent, Session, depth) and `hidden`. Its first user message is the goal, plus the context under "Context:"; the parent's transcript never reaches it. A foreground call returns the child's final assistant message and Session id. A failed run fails the call.
- **Questions and Approvals** go through the child Session's own brokers; the user answers them there and the parent keeps waiting.
- **Depth.** `delegationDepth` in `agent.yaml` (default 2). A Session whose depth has reached it is not offered `delegate`, `delegate_wait` or `subagents`.
- **Stopping.** Stopping a Session stops its running descendants, recursively. Cancelling a parent's foreground call stops its child.
- **Scope.** `sessions_list`, `sessions_search`, `sessions_read` and `session_status` cover the caller Agent's Sessions and the children it delegated, never a hidden one.
- **Hidden delegation.** The target must be a native Agent on a provider whose `onDevice(model)` is true (the `model` override counts); otherwise the call fails with the provider and model named. External Agents are refused. The child is skipped by Reflection, its Agent's Memory, Knowledge and Skills tools are withheld for that Session, and Notifications about it carry no content (a delegated Session's finish or error is never notified). A Hidden Session can only delegate hidden. With `review`, an Approval in the parent's Session shows the answer; deny returns "The user withheld the result." The user still sees Hidden Sessions in the app.
- **On-device parent, cloud child.** The child gets only the goal and context the parent wrote.
- **Not yet.** An unanswered child question or Approval blocks the parent without a timeout. Reviewing an External parent's hidden result uses an Approval in its execution, as for other Kinuvo Approvals. Sessions are found by scanning each Agent's Sessions, which is fine at today's sizes.

## Roadmap

1. **PR 1, read tools (done).** `agent_tools` group: `agents_list`, `sessions_list`, `sessions_search`, `sessions_read`, `session_status`, all Low risk. Native tools and the MCP endpoint with ACP injection. The `hidden` flag on Sessions lands, with exclusion enforced in the tools.
2. **PR 2, delegation (done).** As above.
3. **PR 3, management tools.** `agent_update`, `agent_create` (blank), `mcp_add`, `mcp_remove`, `mcp_test`, `mcp_login`, `session_configure`. All High risk, with a diff shown in the Approval. An Agent can never raise its own Risk tolerance or add Trust rules.
4. **Later.** Board handoffs between Agents (undecided) and Teams.
