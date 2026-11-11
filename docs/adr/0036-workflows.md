---
title: "Workflows"
description: "Durable step graphs with Agent turns and user Approvals, independent of Teams."
---

Status: Accepted

Workflows are Server-owned DAGs with multiple roots. Each step starts as soon as all its predecessors are Done; independent branches run in parallel. IDs are unique, edges must exist, cycles are rejected, and prompt references (`{{steps.<id>.output}}`) must name ancestors. Optional positions persist the editor layout. Each run snapshots its graph so edits cannot alter work already underway.

Agent turns start ordinary Sessions through the existing run executor, retaining its tool policy and pending interactions. `New` creates a Session each run; `Continue` reuses that step's Session from the latest finished run. A Workflow has at most one active run. Explicit Approval steps create a Session and publish the same pending interaction as tools, answered through `session.respond`; no separate Approval endpoint or policy exists. They cannot create Trust rules. Denial fails the step and run. Cancellation stops running Sessions; a failed step cancels sibling work and skips remaining steps.

The Store atomically saves graphs, schedule cursors, run snapshots and step outputs. After restart unfinished runs and nonterminal steps become Cancelled, preserving completed outputs. Execution and Approval waits are not replayed. The existing Scheduler checks Workflow schedules as well as Routines; missed recurring slots fire once, overlapping scheduled runs are skipped, and one-shot triggers disable after firing.

Routines and the Heartbeat retain their existing executor and tests. A Routine remains the scheduled single-prompt form of a Workflow in the domain; migrating its storage and execution to one-step graphs is follow-up work, avoiding changes to its silence, reporting and Approval-timeout behavior here. Workflows have no Teams dependency. Graph editing and run rendering belong to client UI; shared client state and actions live in client/app.
