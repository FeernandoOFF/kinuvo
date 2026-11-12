---
title: "Teams"
description: "Lasting shared conversations with member Sessions, mentions and bounded handoffs."
---

Status: Accepted

## Decision

A Team owns its shared log and one Session per member Agent. Member Sessions stay outside normal chat listings. Native and external members use the same execution dispatch; hosts determine which harnesses they support.

The Server resolves `@agent-name` or `@id` mentions against the Team's members, ignoring case. Multiple mentions run sequentially in first-mention order, with duplicates removed; no matching mention selects the default member (initially the first). Names that contain spaces use the Agent id. Membership and default changes are allowed between turns.

Each turn receives shared log entries since that member's durable cursor: user messages, final replies and handoff notes, never another member's tools or reasoning. Member Session history retains its own work. Replies and notes carry typed Agent ids. Team metadata, log and cursors persist in the existing Store; running work does not resume after restart.

`team_handoff` is offered only during a member's turn through ToolService, including KinuvoMCP. It queues another member after the current reply. Each user message permits four handoffs and shares one Activation budget across its members and Sub-agents. Cancelling the Team cancels the running member and remaining routing.

Approvals and questions keep their Session's pending-interaction pipeline. Team snapshots attribute them to their member; answering on the Team routes to the originating Session. `teams.get` supplies catch-up and `teams.changed` publishes snapshots, including deletion. The client reconnects by reloading Teams.

## Consequences

Teams provide deterministic mention and handoff routing without parallel turns or an autonomous moderator. Snapshots currently carry the complete shared log; paged log delivery is future work.
