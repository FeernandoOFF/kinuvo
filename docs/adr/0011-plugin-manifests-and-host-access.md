---
title: "Plugin manifests describe contributions; the host grants access"
description: "Let plugins declare contributions while the host grants resource access."
---

Status: Accepted

## Context

Integrations need explicit contributions and host-controlled resource access.

## Decision

Manifest format 1 describes a plugin's tools, typed providers, settings schemas and requested named resources. Native plugins derive their manifest from their typed contributions and declare per-run tool discovery; static manifests must declare every exported tool. Unsupported formats fail before activation. The same data contract is intended for a future WASM adapter, without changing provider roles or model tool identities.

`PluginHost` supplies HTTP, files and bound values to one plugin instance. A manifest requests access; separate host-owned grants bind resource names to endpoints, file roots and values or Secret references. Calls deny missing or undeclared grants, recheck grants on use and after reference resolution, bound request/response sizes and disable HTTP redirects. Files use relative paths and check resolved roots, including symlinks. Native code remains trusted process code; this interface is not an OS sandbox and its path checks do not protect against another process changing filesystem links concurrently.

ntfy and Firecrawl exercised the interface first, then the Todoist and CalDAV task providers. For these compiled providers, explicitly selected service or Tasks configuration supplies their named endpoint and credential bindings, endpoints included (a provider with one public API gives it as a setting's default rather than granting it to itself); discovery and saving never expand references. They receive no direct HTTP client or global Secret resolver. User-managed grant persistence, installation review and the Chasm runtime are later steps; future guest manifests must not grant themselves access. Existing tool-call Approvals remain separate from host resource grants.

## Consequences

Native plugins remain trusted in-process code; host grants are not an OS sandbox.
