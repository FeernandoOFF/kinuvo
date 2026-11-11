---
title: "Module layering and standalone harness"
description: "Separate apps, client state, protocol, Server and the built-in harness, with enforced module edges."
---

Status: Accepted

## Context

`harness/core` mixes model building, Server services and tools. Plugins and providers depend on it wholesale, `shared/ui` depends on Server modules, and native execution bypasses the execution contract. Planned features (KinuvoMCP, Sub-agents, Teams, Workflows) need clear seams.

## Decision

Target layout:

- `protocol`: wire and domain values (typed IDs, session entries, events, tool identity, neutral tool descriptors, `WireError`).
- `client/`: `sdk` (transport), `app` (repositories, ViewModels, the `EmbeddedServer` interface), `ui` (rendering only, no Server dependencies).
- `harness/`: `api` (execution contracts), `builtin` (the native harness), and the adapters `acp` and `remote`.
- `models`: verified model downloads.
- `server/`: `plugin-api`, `core`, `rpc`, and the hosts `jvm` and `embedded`.
- `providers/`, `plugins/`, `gateways/` (`acp-agent`, `mcp`), `apps/`, `testing/`.

**Standalone harness.** `harness/api` and `harness/builtin` depend on no `server/*`, `client/*`, plugin or gateway module. Protocol values are allowed. `harness/api` is Koog-free; only `harness/builtin` knows Koog and converts neutral tool descriptors to it.

**Two SPIs.** Providers implement `ModelSource` (in `harness/builtin`) and `ProviderAdmin` (in `server/plugin-api`: auth, keys, usage, downloads, images). Plugins depend only on `server/plugin-api`, which is Koog-free and holds contracts and pure helpers; stateful implementations live in `server/core`.

**Core and hosts.** `server/core` depends on `harness/api` only. Hosts register each provider's `ModelSource` with builtin and hand core an `ExecutionFactory`. Core starts chat, Reflection and Routine runs through it, with the run kind in the request. Apps provide the embedded Server through the existing `EmbeddedServer` interface; `client/ui` never depends on Server modules.

**Enforcement.** A test parses `module.yaml` dependency edges against an explicit allowed-edge list, including adapter and host edges.

Where today's `harness/core` code goes (the plugin-facing contract goes to plugin-api, its stateful implementation to core):

| Today | Goes to |
|---|---|
| Agent loop, model building, profiles and tiers, compaction, result limits, Reflection and Routine graphs | `harness/builtin` |
| Agent config, agent docs and skill values as storage port | `harness/api` |
| Provider admin, Browser, Computer, Notifier, Screens, MCP management, plugin host, tool and core-tool metadata contracts, task, knowledge and web-search contracts, secret-reference syntax, shell risk parsing, voice | `server/plugin-api` |
| Approvals, Instructions, tool registry, Toolsets, Tool scope, all built-in tools, store, Knowledge, Routines, Tasks, Artifacts, secrets and Keychain storage | `server/core` |
| Deterministic JSON | `protocol` |

Plugin-facing contracts extracted from implementation code: `PluginStorage` replaces plugins' direct `AgentFiles` use, `SecretResolver`, `SecretProvider` and `Keychain` are interfaces (core keeps `Secrets` and the OS keychains; hosts hand plugins a `Keychain`), and the core tool metadata table that `CoreTool` defaults to lives in plugin-api beside `CoreToolEntry`. `Agent` and `AgentConfig` moved to `harness/api`, and tool names and groups to `protocol`.

## Consequences

The built-in harness can run without a Server. Providers receive credentials and never read agent files. Removing UI-to-Server edges moves iOS and Android host code into the embedded host and app modules. New edges require an entry in the allowed-edge list.
