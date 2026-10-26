---
title: "Server configuration"
description: "Every config.yaml field, with types, defaults and examples."
---

The Server home contains `config.yaml` (usually `~/.kinuvo/config.yaml`). It holds Server-wide settings. Missing fields use the defaults below; saves omit defaults. Settings writes are serialized and the file is owner-only because options may contain API keys.

MCP stays in `mcp.json`, environment values in `.env`, and Agent settings in [agent.yaml](agent.md). Listen addresses, ports and HTTPS deployment are [CLI options](cli.md), not fields here. No `voice` field is shipped.

## Top-level fields

| Field | Type | Default | Example / meaning |
| --- | --- | --- | --- |
| `timezone` | string or null | `null` | `Europe/Dublin`; unset uses the Server's time zone |
| `tracing` | boolean | `true` | `false`; restart to change [tracing](../operate/tracing.md) |
| `knowledge` | object | Defaults below | Shared Knowledge settings |
| `reflection` | object | Defaults below | Model selection for Reflection |
| `tasks` | object | Defaults below | Task provider and Board settings |
| `search` | object | `{provider: null, options: {}}` | Web search service |
| `notifications` | object | `{provider: null, options: {}}` | Notification service |
| `rules` | object | Empty lists below | Trust and Deny rules for every Agent |

## Knowledge

| Field | Type | Default | Example / meaning |
| --- | --- | --- | --- |
| `knowledge.path` | string or null | `null` | `/home/me/notes`; Markdown defaults to `<Server home>/knowledge` |
| `knowledge.reflection` | boolean | `true` | `false` disables automatic Reflection |
| `knowledge.provider` | string | `markdown` | `anytype` when that plugin is available |
| `knowledge.options` | map of string to string | `{}` | Provider settings below |
| `knowledge.reflectionPrompt` | string or null | `null` | A custom Reflection instruction; null uses the built-in prompt |

The Markdown provider uses `path`. Anytype uses these `options` keys:

| Key | Type | Default | Example / meaning |
| --- | --- | --- | --- |
| `url` | string | `http://127.0.0.1:31009` | Anytype HTTP(S) API base URL |
| `apiKey` | string | Required | `${pass://Kinuvo/Anytype/key}` |
| `space` | string | Required | Your Anytype space ID |

Conventions are stored separately in `conventions/<provider>.md`. See [notes](../guides/notes.md).

## Reflection model

| Field | Type | Default | Example / meaning |
| --- | --- | --- | --- |
| `reflection.provider` | string or null | `null` | `codex`; unset uses the effective Session provider |
| `reflection.model` | string or null | `null` | A usable tool-capable model ID; unset falls back to the effective Session model |

Choose a compatible provider and model together. If the model is missing, unavailable or not tool-capable in discovery, Reflection uses the effective Session model.

## Tasks

| Field | Type | Default | Example / meaning |
| --- | --- | --- | --- |
| `tasks.provider` | string | `local` | `reminders` on a Server offering Apple Reminders |
| `tasks.lists` | map of string to string | `{}` | `{Personal: default}` maps list name or ID to Agent ID; empty shows all lists; an empty Agent value leaves it unassigned |
| `tasks.defaultList` | string or null | `null` | `Personal`; null selects the first available list |
| `tasks.startBeforeDays` | integer | `3` | `1`; how many days before a due date an assigned Task becomes ready |
| `tasks.pollMinutes` | integer | `5` | `10`; provider polling interval |

## Search and Notifications

Both use `ServiceSettings`:

| Field | Type | Default | Example / meaning |
| --- | --- | --- | --- |
| `search.provider` | string or null | `null` | `firecrawl`; null selects no service |
| `search.options` | map of string to string | `{}` | Firecrawl settings below |
| `notifications.provider` | string or null | `null` | `ntfy`; null selects no service |
| `notifications.options` | map of string to string | `{}` | ntfy settings below |

Provider option values are strings and support `${VAR}` and [Secret references](secrets.md).

| Provider | Option | Default | Example / meaning |
| --- | --- | --- | --- |
| Firecrawl | `apiKey` | Required | `${pass://Kinuvo/Firecrawl/key}` |
| Firecrawl | `url` | `https://api.firecrawl.dev` | Change for a self-hosted service |
| ntfy | `topic` | Required | A long random topic subscribed to in the ntfy app |
| ntfy | `url` | `https://ntfy.sh` | Change for a self-hosted service |
| ntfy | `token` | Unset | Access token for a protected topic |

## Trust and Deny rules

| Field | Type | Default | Example / meaning |
| --- | --- | --- | --- |
| `rules.trusted` | list of strings | `[]` | Stable tool identities offered by the Server |
| `rules.shell` | list of strings | `[]` | `["git status"]`; parsed command prefixes, exact matches for High Risk |
| `rules.deny` | object | `{tools: [], shell: []}` | Deny rules win over Trust rules |
| `rules.deny.tools` | list of strings | `[]` | `["shell"]`; stable tool keys or local names |
| `rules.deny.shell` | list of strings | `[]` | `["rm *"]`; command globs |

Use the app's Approval controls to save tool Trust rules with the correct identity. [Approvals](../concepts/approvals.md) explains their limits.

## Example

This example selects a Markdown folder, keeps automatic Reflection, assigns one Task list and enables Firecrawl search. Replace the path and Secret reference for your Server.

```yaml
timezone: Europe/Dublin
knowledge:
  provider: markdown
  path: /home/me/notes
  reflection: true
tasks:
  provider: local
  lists:
    Personal: default
  defaultList: Personal
search:
  provider: firecrawl
  options:
    apiKey: ${pass://Kinuvo/Firecrawl/key}
rules:
  deny:
    shell: ['rm *']
```

Sources: [ServerConfig](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/core/src/store/ServerConfig.kt), [KnowledgeConfig](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/core/src/knowledge/KnowledgeConfig.kt), [TasksConfig](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/core/src/tasks/Tasks.kt), [ServiceSettings](https://github.com/FeernandoOFF/kinuvo/blob/main/shared/protocol/src/Services.kt), [TrustRules](https://github.com/FeernandoOFF/kinuvo/blob/main/shared/protocol/src/Approval.kt), [ADR 0014](../adr/0014-one-config-file.md).
