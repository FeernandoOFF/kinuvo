---
title: "Agent configuration"
description: "Every agent.yaml field and the documents stored beside it."
---

Each Agent has `agents/<id>/agent.yaml` under the Server home. These are the file defaults; onboarding or a host's default provider can choose different values for a new Agent. Session settings can override provider, model, reasoning and Risk tolerance for one Session.

| Field | Type | Default | Example / meaning |
| --- | --- | --- | --- |
| `provider` | string | `codex` | `openai`, `apple` or `local` when offered by the Server, or `custom/<key>` for a configured endpoint |
| `model` | string | `gpt-5.6-sol` | A model ID from the selected provider |
| `reasoning` | string | `medium` | A supported effort from the model picker |
| `reasoningSummary` | string | `auto` | `auto`, `concise`, `detailed` or `none` |
| `tier` | string or null | `null` | `NoTools`, `Micro`, `Small`, `Mid` or `Frontier`; overrides the model's tier. Unset follows the provider or an inference |
| `tools` | list of strings | All optional groups below | `[memory, web_fetch, skills]` |
| `skillDirs` | list of strings | `[]` | `["/home/me/shared-skills"]`; extra read-only Skill folders |
| `compactAtTokens` | integer | `200000` | `100000`; estimated context threshold for Compaction |
| `approvals` | string | `default` | `strict`, `default`, `relaxed` or `full` Risk tolerance |
| `exec` | string | `none` | `none`, `host`, `container` or `ssh` |
| `workspace` | list of strings | `[]` | `["/home/me/work"]`; commands start in the first folder |
| `env` | map of string to string | `{}` | `{API_TOKEN: "${pass://Kinuvo/API/token}"}`; extra container/SSH environment |
| `container` | object | Defaults below | Used by `exec: container` |
| `ssh` | object or null | `null` | Used by `exec: ssh`; `host` is required in the object |
| `avatar` | string or null | `null` | An avatar artboard ID selected in the app |
| `rules` | object | Empty Trust and Deny rules | Same shape as Server rules, scoped to this Agent |

Default optional tool groups:

```yaml
tools: [agent_manage, memory, web_fetch, skills, schedule, knowledge, tasks, ask_user, artifact, device_tools, run_code, computer]
```

A group is offered only when its provider and required capabilities are available. Shell and file tools come from the configured Exec backend; `exec: none` disables them.

## Container and SSH

| Field | Type | Default | Example / meaning |
| --- | --- | --- | --- |
| `container.image` | string | `docker.io/library/debian:stable-slim` | An image containing the tools you need |
| `container.network` | boolean | `false` | `true` enables container networking |
| `ssh.host` | string | Required | `buildbox.example` |
| `ssh.user` | string or null | `null` | `me`; unset uses SSH's default |
| `ssh.port` | integer or null | `null` | `2222`; unset uses SSH's default |
| `ssh.identityFile` | string or null | `null` | `~/.ssh/id_ed25519`, on the Server |

For container mode, `workspace` names existing folders on the Server; they are mounted under `/workspace/<folder-name>`. For SSH, use absolute paths on the remote machine. See [Workspaces](../guides/workspaces.md).

## Agent rules

| Field | Type | Default | Example / meaning |
| --- | --- | --- | --- |
| `rules.trusted` | list of strings | `[]` | Tool identities saved by always allow |
| `rules.shell` | list of strings | `[]` | `["git status"]` |
| `rules.deny` | object | `{tools: [], shell: []}` | Agent-scoped Deny rules |
| `rules.deny.tools` | list of strings | `[]` | `["shell"]` |
| `rules.deny.shell` | list of strings | `[]` | `["rm *"]` |

Saving other Agent settings preserves its saved rules. Copying an Agent starts without its rules. Server-wide Deny rules still apply; see [Approvals](../concepts/approvals.md).

## Example

```yaml
provider: codex
model: gpt-5.6-sol
reasoning: medium
approvals: default
exec: container
workspace:
  - /home/me/work
container:
  image: docker.io/library/debian:stable-slim
  network: false
```

## Adjacent documents

| File | Purpose |
| --- | --- |
| `IDENTITY.md` | Name and Identity; the first heading supplies the display name |
| `SOUL.md` | Principles, tone and rules |
| `MEMORY.md` | Agent-curated long-term Memory |
| `USER.md` | User Notes, editable by the user |
| `HEARTBEAT.md` | Built-in Heartbeat checklist |
| `mcp.json` | Agent-scoped MCP configuration |
| `.env` | Agent-scoped environment values |
| `skills/` | Agent-owned Skills |
| `sessions/` | Session transcripts |
| `routines.json` | Routines and their state |

### Toolsets and run scopes

Built-in tools share one Core tool registry for their group, default Risk and capability summaries. Named Toolsets combine tools in code and can include other Toolsets. Existing `tools:` entries keep working; names such as `core`, `web`, `data`, `automation` and `interactive` enable the corresponding available tool families. Tools still depend on the Server's installed integrations and features.

A run can narrow the Agent's tools by name, group or Toolset. Explicit tools skip the Model profile's group filter, while its schema and tool count limits still apply. A run can also require stricter Risk tolerance or ask for individual tools even when trusted. Child scopes intersect the parent's tools and retain its stricter Approvals. Scopes cannot add Trust or override Deny rules; both rules remain per tool.

## Changes from chat

The `agent_manage` Toolset lets an Agent read its own settings, propose changes to its Identity, Soul, avatar, model, reasoning and available tools, create a blank Agent, and manage its own MCP servers. New Agents include it by default; add `agent_manage` to an explicit `tools:` list to opt in. Agents on small models don't get these tools.

`agent_get` reads settings; `agent_update` changes them; `agent_create` starts a blank Agent from defaults. `mcp_list`, `mcp_add`, `mcp_remove`, `mcp_test` and `mcp_login` manage connections; `mcp_login` needs `ask_user`. For sign-in, the Agent shows you a link in a form: open it, sign in and paste the address your browser ends on (it may be an error page). Environment and header values never appear in Approval previews.

`session_configure` changes only the current Session's model, reasoning or Risk tolerance. It can lower Risk tolerance, never raise it. Hidden Sessions retain their pinned settings and receive no other management tools.

Agent changes, new Agents and MCP additions/removals always ask for Approval and cannot be trusted for future calls. Risk tolerance for the Agent, Trust and Deny rules, Exec backend, Workspace and tools unavailable on the Server must be changed in Settings. Shared MCP servers can only be removed there. External Agents do not receive this Toolset yet.
