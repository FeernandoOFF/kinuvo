---
title: "Plugins"
description: "Available integrations for tools, models, notes, Tasks and Notifications."
---

Plugins supply tools, model providers, Knowledge sources, Task providers, search, Notifications and other host integrations. They are included in the Server build; available capabilities depend on the Server and platform. A Skill supplies instructions, while a plugin supplies executable behavior.

| Integration | Contribution |
| --- | --- |
| anytype | Anytype Knowledge source |
| browser | Controlled browser for Agents |
| bitwarden | Bitwarden Secrets Manager Secret reference provider (`bws://`) |
| caldav | CalDAV Task provider (VTODO lists on Nextcloud, Fastmail, Radicale and similar) |
| exec | Shell and file tools using host, container or SSH Exec backends |
| executor | Sandboxed JavaScript Scripts through `run_code`, calling the Agent's other tools under the same Approvals |
| firecrawl | Web search and page extraction |
| mcp | Tools from configured stdio and HTTP MCP servers, including OAuth |
| ntfy | Notifications through ntfy |
| onepassword | 1Password Secret reference provider (`op://`) |
| protonpass | Proton Pass Secret reference provider (`pass://`) |
| reminders | Apple Reminders Task provider where the platform supports it |
| todoist | Todoist Task provider |
| codex | Codex and OpenAI API model providers |
| apple | Native Apple model provider for supported builds |
| koog | Custom providers: configured OpenAI-compatible endpoints (`custom/<key>`) |
| litert | `local` on-device models through LiteRT-LM |
| voice | Downloaded Server voice models |

Available integrations depend on where your Server runs. Use Settings to see what your selected Server offers. Phone Servers do not offer password-manager Secret reference providers. Desktop Computer use requires a separately installed cua-driver; see [Computer use](../guides/computer-use.md).

## Enable an integration

Choose the relevant section in Settings, such as Tasks, Knowledge, Notifications or Model providers, and enter its connection settings. Enable an Agent’s tools under **Tools & MCP**. Use [MCP](../guides/mcp.md) to connect additional tool servers.

Plugins are included in the Server build; there is no in-app plugin installer. A Skill supplies instructions and cannot install an executable integration. [Server configuration](config.md) lists saved settings.
