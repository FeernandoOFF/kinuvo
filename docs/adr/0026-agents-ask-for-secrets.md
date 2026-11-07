---
title: "Agents ask the user for secrets and get references"
description: "Let Agents ask the user for a credential and receive only a Secret reference, never its value."
---

Status: Accepted

## Context

Agents sometimes need a credential the owner hasn't configured, and asking in chat would put its value in the transcript.

## Decision

An Agent that needs a credential (a GitHub token, a password) calls `request_secret` (Low risk, in the `ask_user` tool group, available in Hidden Sessions too) with a `label` and an optional `reason`. The Session shows a card ([`AgentEvent.SecretRequested`](../../shared/protocol/src/AgentEvent.kt)) where the user picks an item from a connected password manager or types a value, or declines; the Server answers it through `secret.respond`. The Agent's result is only a Secret reference: `${pass://…}` for a picked item, or `${secret://<id>}` for a typed value. A decline says so and tells the Agent not to ask in chat. Like `ask_user`, the run waits (the Session shows as waiting, a delegated child counts as waiting on the user, the user is notified), except that a Routine run in `inherit` mode skips it after 30 minutes as it does an Approval.

The Server checks the answer: a picked reference must be a Secret reference to a connected provider, never a `secret://` one. A typed value goes to `SessionSecrets`, in memory on the Server: its id is random, it is scoped to the asking Agent and Session, and it is dropped after 24 hours or when the Server stops. `Secrets` resolves a `secret://` reference only for that Agent and Session, so another Session, another Agent, config and MCP servers can't read it. It isn't a password manager provider and doesn't show in Settings. The value is never written to disk, logs, the transcript, events or notifications; `secret.respond` carries it once, over the connection that already carries the Session. `Secrets.redact` masks every typed value in tool output, resolved or not, for as long as it lives.

References work where the Server resolves them, for the owning Agent and Session:

- **Shell**: an optional `secrets` object (`ENV_NAME` → reference) is resolved and passed as environment variables, never in arguments (stdin for SSH, `-e NAME` for containers). A Secret reference inside the `command` is refused with a pointer to `secrets`.
- **Browser**: when the text to type is exactly one reference, the Server resolves it as it types; the value isn't echoed back and is masked if the page shows it.
- **`mcp_add`**: password manager references keep working in `env` and `headers`; `secret://` ones are rejected, because they don't outlive the Session. The Agent is told to ask the user to save the credential in their password manager and pick it.

External Agents reach `request_secret` over `/mcp` like the other Agent tools ([ADR 0025](0025-agent-tools-over-mcp.md)), and the user answers in the External Agent's Session. A `secret://` reference only helps tools that the Server runs, so it is no use to the External Agent itself; a picked password manager reference is only as useful as the External Agent's own tools make it.

## Consequences

Not covered yet: container and SSH `env` in `agent.yaml` and MCP server settings don't take `secret://` references, and the Browser on the Android embedded Server doesn't resolve them.
