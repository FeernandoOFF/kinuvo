---
title: "One config file for the Server, one per Agent"
description: "Kinuvo one config file for the Server, one per Agent."
---

Server-wide settings were spread over `knowledge.yaml`, `tasks.yaml`, `search.yaml`, `notifications.yaml`, `approvals.yaml` and `timezone` in the home folder. They now live in one `config.yaml` (`ServerConfig`), a section each: `timezone`, `knowledge`, `tasks`, `search`, `notifications`, `rules` (Trust and Deny rules for every Agent). New Server-wide settings, such as `voice`, get a section there too. Likewise an Agent's own Trust and Deny rules moved from its `approvals.yaml` into `rules` in its `agent.yaml`, next to its Risk tolerance (`approvals`).

`config.yaml` is owner-only, since options can hold keys (or `${…}` Secret references), and only non-default values are written. Each file is written through one read-modify-write at a time (`updateConfig`, `updateAgentConfig`), so saving one part never loses another's change. Rules belong to `TrustRulesRepository`: saving an Agent's config keeps the rules on disk, and a copied Agent starts without any.

The old files are not read; there's no migration, since the only Server was moved by hand. Not merged: `mcp.json` keeps the format other MCP clients share, `.env` stays an env file, and Knowledge's `types.yaml` stays with the notes. State (`board.json`, `tasks.json`, `reflections.json`, `server-devices.json`) isn't config.
