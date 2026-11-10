---
title: "External Agents use Server-owned execution connections"
description: "Separate external harness execution from Server-owned registration and synchronization."
---

Status: Accepted. Superseded in part by [ADR 0030](0030-server-owned-tools-and-pending-interactions.md): Kinuvo tools and their policy are Server-owned.

## Context

External harnesses own execution while Kinuvo still needs a consistent multi-Device transcript.

## Decision

Kinuvo supports its native harness, existing local ACP v2 installations and selected Agents on another Kinuvo Server. The managing Server owns registrations, connections, client synchronization and the displayed transcript; the executing harness owns model context, instructions, tools and tool policy. A model provider remains part of the native harness and does not represent an external Agent.

ACP v2 supplies the external execution lifecycle: prompt acceptance is separate from foreground completion, questions and Approvals are requests, and connection loss is distinct from successful completion. The SDK and subprocess transport stay in a JVM-only module. Native execution stays in-process. The Server retains its own transcript and pending-interaction handling because ACP alone does not provide paginated client catch-up or multi-Device response ownership. Saved history does not imply that the harness can resume its model context.

Remote Kinuvo connections use dedicated execution grants rather than management credentials. The source checks Agent exposure and grant-owned Session access for every operation. Source tool policy is authoritative; revocation denies further access. The initial remote adapter polls source snapshots, and does not relay remote Agents again, preventing connection cycles. Native and external execution share one dispatch contract; native model history remains internal. Execution contracts stay multiplatform, while the ACP SDK and subprocess transport remain JVM-only.

External runtime installation and account configuration remain with the user. Controls are shown only when supported; external Routines, automatic policy substitution and native Reflection are not implied by registering an external Agent.
External Agents expose only supported controls; registering one does not copy its Soul, Memory, Skills or tool policy into Kinuvo. Account sign-in and runtime installation stay on the harness host.

## Consequences

Registering an external Agent does not imply native Reflection, Routines or resumable harness context.
