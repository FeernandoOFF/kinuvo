---
title: "Agent-authored Workflows"
description: "Agents save proven chat work as repeatable graphs through the existing tool gate."
---

Status: Accepted

The workflows tool follows schedule's action-based interface: list, get, save, run, status, cancel, retry and delete. It is enabled by default as its own tool group and respects the existing tool scope. Hidden Sessions do not receive it. Saves decode the same JSON as CLI import and default an omitted agentId to the calling Agent. Server graph validation errors return through the tool gate so the model can repair its graph.

Reads need no Approval. Mutations always ask and cannot create Trust rules, following Agent management. Starting a run can wait for up to 120 seconds (60 by default), returning early when a Review or Approval needs the user. Results include run id, per-step states and errors, and output. Status retrieves the same snapshot later.

A Workflow Tool step has no calling Session and cannot invoke run or retry, including through run_code and the tool catalog. Surface steps create a Session to retain the Surface call and result, scoped to that Surface tool alone. Agent turns retain their ordinary chat tools and Approval policy.
