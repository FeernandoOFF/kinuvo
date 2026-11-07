---
title: "HTML Surfaces and MCP Apps"
description: "Isolate HTML Surfaces and MCP Apps behind one sandboxed host bridge."
---

Status: Accepted

## Context

Native components cannot express every interactive layout, and MCP tools can supply their own UI.

## Decision

Host HTML Surfaces and MCP Apps through one MCP Apps bridge in sandboxed web content, with no separate Kinuvo JavaScript API. Deny network access by default; MCP resource metadata may allow bounded HTTPS origins. Resolve app tool calls from stored Session results and apply the Agent’s ordinary Approval policy. HTML Surfaces cannot call MCP tools.

## Consequences

Untrusted pages remain isolated from host storage and native APIs. Platform web views only bridge messages; shared logic owns the host protocol. Unsupported requests fail explicitly. Windows and Linux rendering, persistent app state and native iOS rendering remain future work.
