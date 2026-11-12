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
- `providers/`, `plugins/`, `gateways/` (`acp-agent`, `kinuvo-mcp`), `apps/`, `testing/`.

**Standalone harness.** `harness/api` and `harness/builtin` depend on no `server/*`, `client/*`, plugin or gateway module. Protocol values are allowed. `harness/api` is Koog-free; only `harness/builtin` knows Koog and converts neutral tool descriptors to it.

**Two SPIs.** Providers implement `ModelSource` (in `harness/builtin`, which extends the Koog-free `ModelPolicy` of `harness/api`: profile and tool acceptance) and `ProviderAdmin` (in `server/plugin-api`: auth, keys, usage, downloads, images). Plugins depend only on `server/plugin-api`, which is Koog-free and holds contracts and pure helpers; stateful implementations live in `server/core`.

**Core and hosts.** `server/core` depends on `harness/api` only. Hosts register each provider's `ModelSource` with builtin (`NativeExecutionFactory`) and hand core that `NativeHarness`, an `ExecutionFactory` that also builds the loop of a composed turn. Core pairs each provider's `ModelPolicy` with its admin, composes the tools, instructions and model choice of a run as a Koog-free `NativeRun` (or `NativeReflection`), and starts chat, Reflection and Routine runs through the factory, with the run kind in the request. Model profiles, tiers, result limits and tool schema levels are `harness/api` vocabulary. Apps provide the embedded Server through the existing `EmbeddedServer` interface, bound by one Koin module (`apps/android-embedded`) or framework module (`apps/ios-embedded`) that depends on `client/ui` and `server/embedded`; `client/*` never depends on Server modules.

**Routes.** `server/core` defines the `Routes` dispatch interface, `ServerServices` (what a handler calls on) and `Caller` (the connection a request arrived on). `server/rpc` implements `Routes` with one route per protocol `Method`, grouped by feature, and may depend on `server/core`, `server/plugin-api`, `harness/api` and `protocol`; core never depends on it. Hosts and server tests pass `serverRoutes()` to `ServerRuntime`, which keeps connection lifecycle, the handshake, frame I/O, presence and subscriptions. Domain rules stay in core services, not in route handlers. A test in `server/rpc` fails on a `Method` with no route, two routes, or no owner.

**MCP gateway.** `gateways/kinuvo-mcp` may depend on `server/core`, `harness/api` and `protocol` to expose the existing tool service. Its JVM host installs it; harness adapters receive only a neutral descriptor. The directory avoids Amper's duplicate module name with `plugins/mcp`. Its integration tests may depend on `harness/acp` and `testing/*` for external-process tests against a real Server.

**Enforcement.** `ModuleEdgesTest` in `testing/server-testing` parses `module.yaml` dependency edges against explicit per-layer rules, including the adapter and host edges, and checks that `harness/api` and `harness/builtin` reach only `harness/*` and `protocol`, and that `client/*` reaches only `client/*` and `protocol`. Test dependencies may also reach `testing/*`, and Server-side modules may use `client/*` in tests. Edges outside the rules need a commented entry in the test's exception list, which is empty today.

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

The built-in harness can run without a Server. Providers receive credentials and never read agent files. Removing UI-to-Server edges moves iOS and Android host code into the embedded host and app modules: the Android WebView browser runtime into `server/embedded`, `ServerService` and the Server binding into `apps/android-embedded`, and the Swift bridges and iOS Server start into `apps/ios-embedded`. Web Push encryption lives in `protocol` because both the Server and the client app use it. A new edge must fit a rule in the test or be added to it together with this ADR.
