---
title: "KinuvoMCP"
description: "Offer Server tools to local HTTP-capable external harnesses with revocable Session credentials."
---

Status: Accepted

## Context

External harnesses need the Server's tools under its Tool scope and Approvals, without moving Server policy into ACP or creating another tool invocation path.

## Decision

The JVM host installs `gateways/kinuvo-mcp`, a private loopback Streamable HTTP MCP endpoint with JSON replies. The directory distinguishes it from `plugins/mcp`, since Amper requires unique module names. The gateway may depend on `server/core`; it composes the run's existing ToolService, including redaction, policy, limits and audit events. External Agents use Server tool defaults and their Session settings; private harness tools keep their own configuration. The gateway shares the built-in harness's text limiter and keeps full tool results in the Session log. The embedded Server does not install it.

A cryptographically random bearer credential identifies one execution Session and Agent. The gateway issues it before the first prompt, checks it using constant-time digest comparison and revokes it on connection loss or close. Each turn installs its own Tool scope, policy and limits behind the credential. Tool calls between turns receive a JSON-RPC error. Only harnesses advertising HTTP MCP and Session resume receive KinuvoMCP. `harness/api` carries a neutral descriptor with no Server or Koog dependency.

Approvals and questions use the Session's pending-interaction pipeline. An ACP Approval explicitly naming the offered `kinuvo` MCP server and one of its tools is allowed once: ToolService remains authoritative. Other requests retain their existing handling, including private harness tools.

## Consequences

KinuvoMCP calls have the same policy and observable Session activity as built-in tool calls. ACP reuses the loaded Session while the endpoint and credential stay the same. This endpoint serves local external harnesses only; remote exposure and Sub-agents are separate work.
