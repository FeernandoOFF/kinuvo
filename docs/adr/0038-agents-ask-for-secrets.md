---
title: "Agents ask for secrets with a private card"
description: "request_secret returns a Secret reference; picked or typed values resolve only on the Server."
---

Status: Accepted

## Decision

`request_secret` is a Server tool in the `ask_user` group, invoked through ToolService like `ask_user`, including through KinuvoMCP. It takes a `label` and a `reason` and raises a `secret` pending interaction in the Session's one pipeline: one `InteractionRequested` event and one `session.respond` answer. Answering it needs no management access. Sub-agents relay it like other pending interactions; Hidden Sessions don't offer it, since they have no tools that take references.

The user answers privately in one of three ways:

- Pick a field in a connected password manager, browsing vault, item and field by name only. The Agent gets the reference, e.g. `${pass://Vault/Item/field}`; the Server checks its scheme belongs to a connected provider.
- Type the value. The Server keeps it in memory only, for 24 hours, scoped to the asking Agent and Session, and drops it when the Session is deleted. The Agent gets `${secret://<id>}`, which other Agents and Sessions can't resolve.
- Decline. The Agent is told not to ask for the credential in chat.

References resolve only on the Server, through `SecretResolver.scoped(agent, session)`: `shell`'s `secrets` argument sets environment variables (never arguments), foreground only, and `browser_type` types a `text` that is a single reference. Values never appear in the transcript, events, Notifications, logs or disk; tool output masks values still in memory, typed ones included, of four or more characters.

## Consequences

Agents can use credentials without the user pasting them into chat. A typed value is lost on Server restart, so the Agent asks again. ACP clients of the `acp-agent` gateway can't answer privately, so their requests are declined. Future work: `mcp_add` environment and headers, and the embedded Android Browser, which doesn't resolve references yet.
