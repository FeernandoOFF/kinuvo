---
title: "Model, harness and tools"
description: "Understand the three parts of an Agent and where each runs."
---

An Agent combines a **model**, the **harness** that runs it, and **tools** it can use. Those parts can run in different places.

| Part | What it does | Where it runs |
| --- | --- | --- |
| Model | Reads the Session and chooses replies or tool calls | Codex and OpenAI process requests in the cloud. The Apple provider supports an on-device model and Private Cloud Compute where the native build offers them. |
| Harness | Runs the Agent loop, loads its context, checks Approvals and keeps its data | The Server: on a laptop, home server, in Docker, or embedded in a supported app, including on a phone. |
| Tools | Read or change things the Agent works with | Server plugins, local or remote MCP servers, an Exec backend, or tools shared by paired Devices and Computers. |

The Server advertises the providers and capabilities it actually has. An embedded Server is still a Server: the same Agent, Session and Approval concepts apply. Its lifetime and background availability depend on the platform.

A self-hosted Server can use a cloud model. An on-device model does not imply that every tool is local: web search, MCP and Device tools may reach other systems. Choose all three parts to match the work.

The Apple provider limits the tools it offers to fit its models, so it does not expose every tool available to a larger cloud model. Use the model picker for availability and [models](../reference/models.md) for provider details.

See [plugins](../reference/plugins.md), [MCP](../guides/mcp.md), [Workspaces](../guides/workspaces.md) and [Computer use](../guides/computer-use.md).

Implementation: [Apple provider](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/providers/apple/src/AppleProvider.kt), [embedded Server](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/server/embedded/src/EmbeddedServerHost.kt).
