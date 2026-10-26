---
title: "Plugins"
description: "The compiled contributions that give a Server tools and providers."
---

Plugins supply tools, model providers, Knowledge sources, Task providers, search, Notifications and other host integrations. They are compiled into the host today; available capabilities depend on the Server and platform. A Skill supplies instructions, while a plugin supplies executable behavior.

| Module | Contribution |
| --- | --- |
| `harness/plugins/anytype` | Anytype Knowledge source |
| `harness/plugins/exec` | Shell and file tools using host, container or SSH Exec backends |
| `harness/plugins/executor` | Sandboxed JavaScript Scripts through `run_code`, calling the Agent's other tools under the same Approvals |
| `harness/plugins/firecrawl` | Web search and page extraction |
| `harness/plugins/mcp` | Tools from configured stdio and HTTP MCP servers, including OAuth |
| `harness/plugins/ntfy` | Notifications through ntfy |
| `harness/plugins/protonpass` | Proton Pass Secret reference provider |
| `harness/plugins/reminders` | Apple Reminders Task provider where the platform supports it |
| `harness/providers/codex` | Codex and OpenAI API model providers |
| `harness/providers/apple` | Native Apple model provider for supported builds |

The JVM host installs the tool and service plugins above, Codex/OpenAI model providers, and a Proton Pass Secret reference provider. Embedded hosts assemble their own available plugins. Installing a plugin module in source does not mean every Device can use it.

## Manifests and host access

Manifest format 1 describes tools, typed providers, settings schemas and named resources a plugin requests. Unsupported formats fail before activation. The host grants specific endpoints, file roots and values or Secret references; a manifest cannot grant itself access.

Firecrawl and ntfy use this host interface. Selecting their service config binds their endpoint and values. HTTP redirects are disabled, sizes are bounded, and file paths are checked against granted roots. Native code is trusted process code; these checks are not an OS sandbox.

Tool-call Approvals remain separate from host resource grants.

Sources: [ADR 0011](../adr/0011-plugin-manifests-and-host-access.md), [plugin modules](https://github.com/FeernandoOFF/kinuvo/tree/main/harness/plugins), [JVM assembly](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/server/jvm/src/Plugins.kt).

<div class="k-future">

**Future enhancements**

- **Plugin installation** managed by the user, without rebuilding the Server.
- **Saved host-access grants**, so a plugin's endpoints, file roots and values persist across restarts.
- **A Wasm plugin adapter**, for plugins written in other languages.

</div>
