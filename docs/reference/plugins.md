---
title: "Plugins"
description: "The compiled contributions that give a Server tools and providers."
---

Plugins supply tools, model providers, Knowledge sources, Task providers, search, Notifications and other host integrations. They are compiled into the host today; available capabilities depend on the Server and platform. A Skill supplies instructions, while a plugin supplies executable behavior.

| Module | Contribution |
| --- | --- |
| `harness/plugins/anytype` | Anytype Knowledge source |
| `harness/plugins/browser` | Controlled browser for Agents |
| `harness/plugins/bitwarden` | Bitwarden Secrets Manager Secret reference provider (`bws://`) |
| `harness/plugins/caldav` | CalDAV Task provider (VTODO lists on Nextcloud, Fastmail, Radicale and similar) |
| `harness/plugins/exec` | Shell and file tools using host, container or SSH Exec backends |
| `harness/plugins/executor` | Sandboxed JavaScript Scripts through `run_code`, calling the Agent's other tools under the same Approvals |
| `harness/plugins/firecrawl` | Web search and page extraction |
| `harness/plugins/mcp` | Tools from configured stdio and HTTP MCP servers, including OAuth |
| `harness/plugins/ntfy` | Notifications through ntfy |
| `harness/plugins/onepassword` | 1Password Secret reference provider (`op://`) |
| `harness/plugins/protonpass` | Proton Pass Secret reference provider (`pass://`) |
| `harness/plugins/reminders` | Apple Reminders Task provider where the platform supports it |
| `harness/plugins/todoist` | Todoist Task provider |
| `harness/providers/codex` | Codex and OpenAI API model providers |
| `harness/providers/apple` | Native Apple model provider for supported builds |
| `harness/providers/koog` | Custom providers: configured OpenAI-compatible endpoints (`custom/<key>`) |
| `harness/providers/litert` | `local` on-device models through LiteRT-LM |
| `harness/providers/voice` | Downloaded Server voice models (`DownloadedVoice`) |

The JVM host installs the tool and service plugins above, Codex/OpenAI model providers, and the Proton Pass, 1Password and Bitwarden Secret reference providers. Phones' lists live in `harness/server/embedded` (`embeddedPlugins`); they include the Todoist and CalDAV Task providers and no Secret reference provider. The desktop Computer driver (`harness/plugins/cua`) is wired by the desktop app and CLI, not installed as a plugin. Installing a plugin module in source does not mean every Device can use it.

## Manifests and host access

Manifest format 1 describes tools, typed providers, settings schemas and named resources a plugin requests. Unsupported formats fail before activation. The host grants specific endpoints, file roots and values or Secret references; a manifest cannot grant itself access.

Firecrawl, ntfy, Todoist and CalDAV use this host interface. Selecting their service or Tasks config binds their endpoints and values; an endpoint is always a setting, with a default for a provider with one public API (Firecrawl, Todoist). Task providers are definitions like search and notification providers: their saved settings give the task app client, and built-in ones get a host that grants nothing. WebDAV's `PROPFIND` and `REPORT` are allowed alongside the usual HTTP methods for every plugin; grants still decide where they can go. HTTP redirects are disabled, sizes are bounded, and file paths are checked against granted roots. Native code is trusted process code; these checks are not an OS sandbox.

Tool-call Approvals remain separate from host resource grants.

Models see each tool by its own name, e.g. `memory`. When two tools in a run share a name, such as `search` from two MCP servers, each gets a stable alias that includes its plugin and instance. Calls, Approvals and Trust rules route by the tool's identity, so earlier Sessions that recorded aliases keep working.

Sources: [ADR 0011](../adr/0011-plugin-manifests-and-host-access.md), [plugin modules](https://github.com/FeernandoOFF/kinuvo/tree/main/harness/plugins), [JVM assembly](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/server/jvm/src/Plugins.kt).

<div class="k-future">

**Future enhancements**

- **Plugin installation** managed by the user, without rebuilding the Server.
- **Saved host-access grants**, so a plugin's endpoints, file roots and values persist across restarts.
- **A Wasm plugin adapter**, for plugins written in other languages.

</div>
