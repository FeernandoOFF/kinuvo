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

Each model has a tier (Micro, Small, Mid or Frontier) that sets its prompt, tools and step limit. Small models (Apple on-device, LiteRT) get a short basic prompt and a capped tool list, with no Skills, Skill catalog or `show_ui`: Apple's on-device model and Qwen3 0.6B offer 5 tools in 4 steps, Gemma 4 E2B 8 and E4B 10 in 10 steps, Memory and web first. Mid models take up to 50 steps and Frontier models 66, and Mid models reach MCP tools through the tool catalog. Use the model picker for availability and [models](../reference/models.md) for provider details.

See [plugins](../reference/plugins.md), [MCP](../guides/mcp.md), [Workspaces](../guides/workspaces.md) and [Computer use](../guides/computer-use.md).

### Toolsets and run scopes

Built-in tools share one Core tool registry for their group, default Risk and capability summaries. Named Toolsets combine tools in code and can include other Toolsets. Existing `tools:` entries keep working; names such as `core`, `web`, `data`, `automation` and `interactive` enable the corresponding available tool families. Tools still depend on the Server's installed integrations and features.

A run can narrow the Agent's tools by name, group or Toolset. Explicit tools skip the Model profile's group filter, while its schema and tool count limits still apply. A run can also require stricter Risk tolerance or ask for individual tools even when trusted. Child scopes intersect the parent's tools and retain its stricter Approvals. Scopes cannot add Trust or override Deny rules; both rules remain per tool.

Result limits fit tool output to the model. Count parameters are clamped by the harness, including MCP tools and calls from Scripts. Large text results are shortened in the model's prompt with an omission marker and advice to narrow the query. Tools that support paging also suggest offset/limit. The app and saved Session keep the full output, subject to each tool's existing upper bounds.
