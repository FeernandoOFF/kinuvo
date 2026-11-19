---
title: "Kinuvo-aware prompts"
description: "Fit Agent instructions to model tiers and advertise tools only when usable."
---

Status: Accepted

## Decision

Micro and NoTools keep Basic prompts; Small keeps BasicWithIdentity. Mid uses Compact: a short operating brief, current Memory and User Notes, available capabilities, setup guidance, Server platform and date. It omits self-configuration and proactivity guidance. Frontier keeps Full operating instructions and gains an About Kinuvo section derived from the run's advertised tools. Configuration guidance names only tools present in that run; otherwise it points to Settings. Chat cannot loosen safety.

Compact and Full consult Kinuvo documentation through `web_fetch` starting with https://kinuvo.feernandooff.com/llms.txt and fetching the matching owner documentation URL exactly as listed when that tool is advertised. Agents must consult the docs for Kinuvo questions rather than answer from memory.

Enabled tools that need setup stay enabled in Settings but are hidden from the model, along with their instruction sections. Prompt composition and client capability lists share `ResolvedTool.state`. A setup state carries a human reason and documentation path; core entries use ADR 0026's documentation slot. Compact and Full name unconfigured capabilities and guide users to their docs or Settings. Computer use requires an available paired Computer; Browser requires a browser runtime with a usable host installation.

The docs site provides `llms.txt` and plain Markdown copies of owner-facing pages.

## Future work

Separate PRs will add protocol MCP presets with `mcp_add(preset)` following Risk tolerance (amending ADR 0037), and first-session discovery for Frontier. These are not implemented by this decision's initial change.
