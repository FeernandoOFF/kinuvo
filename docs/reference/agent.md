---
title: "Agent configuration"
description: "Every agent.yaml field and the documents stored beside it."
---

Each Agent has `agents/<id>/agent.yaml` under the Server home. These are the `AgentConfig` defaults; onboarding or a host's default provider can choose different values for a new Agent. Session settings can override provider, model, reasoning and Risk tolerance for one Session.

| Field | Type | Default | Example / meaning |
| --- | --- | --- | --- |
| `provider` | string | `codex` | `openai` or `apple` when offered by the Server |
| `model` | string | `gpt-5.6-sol` | A model ID from the selected provider |
| `reasoning` | string | `medium` | A supported effort from the model picker |
| `reasoningSummary` | string | `auto` | `auto`, `concise`, `detailed` or `none` |
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
tools: [memory, web_fetch, skills, schedule, knowledge, tasks, ask_user, artifact, device_tools, run_code, computer]
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

Sources: [AgentConfig](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/core/src/Agent.kt), [nested settings](https://github.com/FeernandoOFF/kinuvo/blob/main/shared/protocol/src/Config.kt), [tool groups](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/core/src/tools/Tool.kt), [file layout](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/core/src/store/AgentFiles.kt).
