---
title: "Workflow runs resume after a restart"
description: "A Running Workflow run survives a Server restart; steps waiting on an Approval are asked again, and interrupted Tool and Agent work fails instead of replaying."
---

Status: Accepted

On start the Server reads every Running Workflow run and settles its unfinished steps from the run's graph snapshot. A Pending step stays Pending. A step waiting for an Approval, whether an Approval step or a Tool step whose call needed one, goes back to Pending with its Session kept: the tool had not run. A Running Model step goes back to Pending, since a model request has no side effects. A Running Tool step or Agent turn may already have had side effects, so it becomes Failed with "Interrupted by a Server restart" and keeps its Session; the run then ends Failed with its remaining steps Skipped, as for any failed step.

A run with no such step stays Running and executes again once the Server's listeners are in place, before the Scheduler starts. Done steps keep their outputs and are not executed again; the rest follow the rules of a fresh run: predecessors settled first, following live Condition edges and propagating Skipped branches, parallel branches, one active run per Workflow, and cancellation through `cancel`.

A step that returns to Pending reuses its Session and publishes a fresh pending interaction there, so the user answers it through `session.respond` as before. Pending interactions live only in memory, so the interaction from before the restart is gone and the Session shows exactly one.
