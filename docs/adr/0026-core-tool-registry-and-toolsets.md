---
title: "Core tool registry and toolsets"
description: "Share built-in tool metadata and compose run scopes without relaxing Approvals."
---

Status: Accepted

## Context

Tool metadata was repeated across implementations, model policy and capability instructions. Runs could narrow tools but could not require stricter Approvals.

## Decision

Core tools explicitly implement `CoreTool` and read metadata from `CoreToolRegistry`: name, group, default Risk, kind, repeatability, summary and optional capability hint. A documentation slot is reserved for future use; no built-in Skills are loaded. Detailed parameter instructions remain with tool descriptors. Plugin and MCP tools remain External by default; they do not inherit metadata by matching a core name. Plugin manifests do not yet declare Risk or group; implementations can still declare them.

Toolsets are Kotlin definitions listing tools and including other toolsets. Resolution rejects duplicate names, missing includes and cycles. Legacy agent.yaml tool names keep their meaning. Composed toolsets expand to the existing enabled tool families; installation and Server features still control availability.

A Tool scope accepts names, groups and toolsets. Explicit selections bypass only the model tier's group filter. A scope can cap Risk tolerance and require individual tools to ask, including when Trust rules match. Deny rules remain per tool and run first. Child scopes intersect both selections and inherit every parent's tightening. Internal bookkeeping tools remain available. The helper creates no sub-agent and grants no new tools.

## Consequences

Adding a built-in requires a registry entry. Model policy, capability hints and default Approvals read the same metadata. Toolsets enable tools but never grant Trust; Trust and Deny rules remain per tool. Tool descriptions retain detailed call instructions alongside their schemas.
