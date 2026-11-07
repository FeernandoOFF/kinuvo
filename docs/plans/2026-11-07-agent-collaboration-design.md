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
- **Hidden delegation.** The target must be a native Agent on a provider whose `onDevice(model)` is true (the `model` override counts); otherwise the call fails with the provider and model named. External Agents are refused. The child starts with that provider and model pinned in its Session settings, so a later change of its Agent's default doesn't move it. The child is skipped by Reflection, its Agent's Memory, Knowledge and Skills tools are withheld for that Session, as are the network-egress tools (`web_fetch`, `web_search`, Browser, Computer, image generation and MCP servers), and Notifications about it carry no content (a delegated Session's finish or error is never notified). A Hidden Session can only delegate hidden. With `review`, an Approval in the parent's Session shows the answer; deny returns "The user withheld the result." The user still sees Hidden Sessions in the app.
- **On-device parent, cloud child.** The child gets only the goal and context the parent wrote.
- **Waiting on the user.** A child whose question or Approval stays unanswered for 30 minutes fails the parent's foreground `delegate` or `delegate_wait` for that child ("waiting on the user"); its Session stays open and keeps counting as running, so stopping the parent still stops it.
- **Not yet.** Reviewing an External parent's hidden result uses an Approval in its execution, as for other Kinuvo Approvals. Sessions are found by scanning each Agent's Sessions, which is fine at today's sizes.

## Roadmap

1. **PR 1, read tools (done).** `agent_tools` group: `agents_list`, `sessions_list`, `sessions_search`, `sessions_read`, `session_status`, all Low risk. Native tools and the MCP endpoint with ACP injection. The `hidden` flag on Sessions lands, with exclusion enforced in the tools.
2. **PR 2, delegation (done).** As above.
3. **PR 3, management tools (done).** The `agent_manage` group, below.
4. **Later.** Board handoffs between Agents (undecided), Teams, a richer diff card for these Approvals (UI), Kinuvo-side identity tools for External Agents, and letting the Server catch the loopback OAuth callback so `mcp_login` doesn't need the pasted address.

## Management tools (PR 3)

- **Group.** `agent_manage`, on by default for new Agents (an Agent with an explicit `tools:` list opts in), native and over `/mcp` for native callers. External callers get none of it: their Kinuvo display identity lives in the registration, which nothing in the Agent store owns, so offering an identity-only subset would need a second path; left for later.
- **Tools** act on the caller's own Agent and Session.
  - `agent_get` (Low). Name, Identity, Soul, avatar, model and provider, reasoning, Risk tolerance (read-only), tool groups, MCP servers.
  - `agent_update` (High). Name, Identity, Soul, avatar, provider and model, reasoning, `enable_tools` and `disable_tools`. Runs through `Admin.update`.
  - `agent_create` (High). Name, optional Identity, Soul, provider and model. A blank Agent from the template, never a clone, so no Memory, Skills, Trust rules or own MCP servers carry over.
  - `mcp_list` and `mcp_test` (Low), `mcp_add` and `mcp_remove` (High), `mcp_login` (Medium). Agent-scoped servers only; `mcp_remove` is for servers the user named. `mcp_login` puts the link in an `ask_user` form and completes with the pasted address.
  - `session_configure` (Medium). The current Session's model and provider, reasoning, and a stricter Risk tolerance. Runs through `HarnessRuntime.configure`, which the user's `session.configure` also uses.
- **Never from chat.** Raising Risk tolerance (Agent or Session), adding Trust rules, removing Deny rules, changing `exec` or `workspace`, and enabling a tool group the Server doesn't offer. Passing such a parameter fails with a pointer to Settings; each has a test.
- **Approvals.** `Tool.describe` builds the before → after text on the Server from the current state; the Approval shows it in its reason, and over MCP it is part of the ACP permission description. The four High tools are `alwaysAsk`, so they ask at every tolerance. A richer diff card is UI work for later.
- **Hidden Sessions** get only `session_configure`, and its model change must stay on-device (see the hidden rules above).
