---
title: "Voice providers: Device and Server speech pipelines"
description: "Separate Device and Server speech providers from Agent models."
---

Status: Accepted

## Context

Voice providers are independent of Agent models and can run on the Device or Server.

## Decision

Keep transcription and synthesis independently selectable, with Device defaults and Server-owned endpoints, keys and downloaded models. Live voice uses a speech-to-speech transport and delegates work through the Session’s Agent; one app-level call owner retains audio across navigation. Persist a completed Voice call as one transcript block, with delegated work kept as separate messages.

## Consequences

Provider failures are visible rather than silently switching providers. Audio clips remain in memory; completed Live voice transcripts enter Session history. Platform audio adapters and lifecycle verification are required.
