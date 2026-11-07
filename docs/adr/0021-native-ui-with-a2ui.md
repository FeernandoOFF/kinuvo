---
title: "Native UI with A2UI"
description: "Render validated A2UI Surfaces with native components and advertised catalogs."
---

Status: Accepted

## Context

Agents need interactive forms that fit native clients and the chat design.

## Decision

Use A2UI catalogs and native renderers for Surfaces. Devices register catalogs after authentication and before becoming ready; the Server validates against the requesting Device’s catalogs. Each tool call creates independent Surfaces, with submitted data restored from Session history. Keep the standard catalog unchanged and advertise Kinuvo additions in a separate catalog.

## Consequences

Shared validation and matching renderers keep advertised components usable. Image fetching goes through a guarded Server proxy; unsupported transports fail closed. Unsubmitted edits are not persisted. A future SwiftUI renderer can reuse the shared model.
