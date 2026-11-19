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

The MCP preset catalog lives in `protocol`, shared by Settings and Agent management. `mcp_list` lists unconfigured presets; `mcp_add(preset)` adds catalog OAuth servers under the Session's Risk tolerance. Token and OwnClient presets direct users to Settings. Frontier checks relevant presets, offers once using advertised interaction tools, and remembers declined or ignored offers when Memory is available. Configured connections with sign-in failures can be reconnected with `mcp_login` when advertised.

Frontier adds first-session discovery to late context only for an interactive user chat send in a normal, non-Routine, non-Team Session with no saved assistant text, voice reply or successful reply card and blank Memory and User Notes. It explores connected apps read-only, summarizes discoveries and offers next steps using advertised tools. Connector offers and decline/ignore events are not recorded during discovery. Only facts the user states or confirms may become User Notes. A concrete request takes priority; without connected apps it introduces itself and offers connection guidance. Later turns omit the block; another new Session qualifies while both documents remain blank.

## Future work

An ACP Session keeps first-turn setup guidance if a Computer is paired mid-session.
