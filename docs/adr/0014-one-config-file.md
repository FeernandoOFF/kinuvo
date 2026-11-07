---
title: "One config file for the Server, one per Agent"
description: "Store Server settings in config.yaml and Agent settings in agent.yaml."
---

Status: Accepted

## Context

Owners need one place for Server settings and one for each Agent.

## Decision

Server-wide settings live in one `config.yaml` (`ServerConfig`), a section each: `timezone`, `knowledge`, `tasks`, `search`, `notifications`, `rules` (Trust and Deny rules for every Agent). New Server-wide settings, such as `voice`, get a section there too. Likewise an Agent's own Trust and Deny rules moved from its `approvals.yaml` into `rules` in its `agent.yaml`, next to its Risk tolerance (`approvals`).

`config.yaml` is owner-only, since options can hold keys (or `${…}` Secret references), and only non-default values are written. Each file is written through one read-modify-write at a time (`updateConfig`, `updateAgentConfig`), so saving one part never loses another's change. Rules belong to `TrustRulesRepository`: saving an Agent's config keeps the rules on disk, and a copied Agent starts without any.

Legacy split config files are not read or migrated automatically. Not merged: `mcp.json` keeps the format other MCP clients share, `.env` stays an env file, and Knowledge's `types.yaml` stays with the notes. State (`board.json`, `tasks.json`, `reflections.json`, `server-devices.json`) isn't config.

## Consequences

Legacy split config files are not migrated automatically; MCP and environment files retain their own formats.
