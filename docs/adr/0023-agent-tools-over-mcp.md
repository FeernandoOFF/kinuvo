---
title: "Agent tools: one service, native and MCP transports"
description: "Kinuvo's tools for Agents are one Server service reached natively or over MCP, scoped to the caller."
---

Tools that let Agents work with one another are one service on the Server. Every call carries a caller scope: the calling Agent, its Session and its delegation depth. The service enforces the scope, so Session tools see only the caller Agent's own Sessions, never another Agent's, and never a Hidden Session. The first group, `agent_tools`, is read-only: `agents_list`, `sessions_list`, `sessions_search`, `sessions_read` and `session_status`. It is a built-in tool group, always sent to the model and on by default.

The same implementation has two transports. Native Agents get the group as built-in tools. External Agents reach it over MCP: the Server serves streamable HTTP at `/mcp`, authenticated by a token minted for one Session when its execution opens and revoked when it closes. When Kinuvo starts an ACP session it passes this server in `session/new` `mcpServers`: HTTP when the harness advertises `mcpCapabilities.http`, otherwise a stdio entry running the hidden `kinuvo mcp-bridge` command, which proxies to the same endpoint and receives its token in the environment, not on the command line. The ACP SDK stays in the JVM-only module. Agents reached through a remote execution grant do not get these tools.

These tools run on the Server, so Kinuvo's own Risk, Approval, Trust and Deny rules govern every call, native or over MCP. An MCP call that needs an Approval shows it in the caller's Kinuvo Session through the execution's Approval broker; Deny rules block it first. Agents never change their own Risk tolerance or Trust rules through these tools.

There is no standalone MCP server for the `kinuvo` CLI yet: the endpoint exists to serve Agents Kinuvo itself runs. Delegation and management tools build on the same service ([plan](../plans/2026-11-07-agent-collaboration-design.md)).
