---
title: "Session inbox"
description: "The Server keeps the user's Settled and Unread Session state across Devices."
---

# Session inbox

## Decision

- The Server stores Settled and Unread in each Session's details for its user. Devices receive both fields in Session listings and through `SessionChanged`.
- A new run, steering input, or a transition to Waiting clears Settled. The user can settle a Session again while it is Running or Waiting.
- A finished reply or Routine report marks the Session Unread unless a foreground Device reports it as the Session on screen. A Device showing the Session in the foreground marks it read.
- Devices use the `SessionInbox` Server feature to enable syncing and migrate their existing settled Sessions. With older Servers, settling stays in Device preferences and unread marks stay in memory.

## Consequences

- Paired Devices share the same inbox state, and it survives a restart. Older Session records default to open and read.
- The feature uses additive protocol fields and keeps the current protocol version.
