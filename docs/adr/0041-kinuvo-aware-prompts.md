---
title: "Kinuvo-aware prompts"
description: "Fit Agent instructions to model tiers and advertise tools only when usable."
---

Status: Accepted

## Decision

Micro and NoTools keep Basic prompts; Small keeps BasicWithIdentity. Mid uses Compact: a short operating brief, current Memory and User Notes, available capabilities, setup guidance, Server platform and date. Its brief covers retrieval, checking current facts, retrying incomplete look-ups, and continuing until verified or blocked without promises of future action; replies stay short. It omits self-configuration guidance. Frontier keeps Full operating instructions and gains an About Kinuvo section derived from the run's advertised tools. Configuration guidance names only tools present in that run; otherwise it points to Settings. Chat cannot loosen safety.

Compact and Full share one proactivity brief: research topics, goals and open-ended requests with available tools before answering with findings and a recommendation. The brief is composed per run in late context, so existing Sessions receive it without changing their frozen instructions. Research uses relevant read-only calls within the user's scope; private Memory, notes and connector content stay out of web-search queries and other external tool arguments unless the user asked for that disclosure. Read-only work proceeds without asking; writes, sends, spending and irreversible actions stay within authorized scope and Kinuvo's Approvals. A missing decision gets one focused question after independent research. Connector offers require a choice but do not delay research with existing tools.

Compact and Full consult Kinuvo documentation through `web_fetch` starting with https://kinuvo.feernandooff.com/llms.txt and fetching the matching owner documentation URL exactly as listed when that tool is advertised. Agents must consult the docs for Kinuvo questions rather than answer from memory.

Enabled tools that need setup stay enabled in Settings but are hidden from the model, along with their instruction sections. Prompt composition and client capability lists share `ResolvedTool.state`. A setup state carries a human reason and documentation path; core entries use ADR 0026's documentation slot. Compact and Full name unconfigured capabilities and guide users to their docs or Settings. Computer use requires an available paired Computer; Browser requires a browser runtime with a usable host installation.

The docs site provides `llms.txt` and plain Markdown copies of owner-facing pages.

The MCP preset catalog lives in `protocol`, shared by Settings and Agent management. `mcp_list` lists unconfigured presets; `mcp_add(preset)` adds catalog OAuth servers under the Session's Risk tolerance. Token and OwnClient presets direct users to Settings. Frontier checks relevant presets, offers once using advertised interaction tools, and remembers only explicitly declined offers, never silence, when Memory is available. Configured connections with sign-in failures can be reconnected with `mcp_login` when advertised.

Frontier adds first-session discovery to late context only for an interactive user chat send in a normal, non-Routine, non-Team Session whose persisted log contains only the current user message (ignoring initial instructions, settings, details and Skill activation markers) and with blank Memory and User Notes. Composition precedes saving the current message, so the eligibility check requires no earlier conversation entries. The chat composer opts in only when it creates the Session, using an additive, optional, default-false send field; caller credentials alone cannot distinguish interactive chat from automation or gateways. Remote grants, gateways, automated sends and Live voice delegation do not opt in. It explores advertised external MCP read tools, even without Agent management; `mcp_list` is used for enumeration only when advertised. It leads with discoveries and a top recommendation, then offers 3–5 next steps using advertised tools and does the first cheap read-only follow-up within its look-up limit. Add to tasks still requires the user to choose. Connector offers and declines are not recorded during discovery. Only facts the user states or confirms may become User Notes. A concrete request takes priority; without connected apps it introduces itself and offers connection guidance. Later turns omit the block; another new Session qualifies while both documents remain blank.

Queued or undelivered steering messages, failed first turns, voice transcripts, MCP App results and generated images all prevent later discovery once any conversation entry is saved. A Session created before the composer send (including Live voice) does not opt in. Older clients omit the field and do not get discovery.

## Future work

An ACP Session keeps first-turn setup guidance if a Computer is paired mid-session. Speech fallback sends never opt in to discovery. Discovery wording treats a connector as read-only from its Risk, not the MCP read-only annotation, and may take the no-apps introduction when connected tools sit behind the tool catalog; both only change the wording, never what Approvals allow.
