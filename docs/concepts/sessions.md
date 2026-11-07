---
title: "Sessions"
description: "Bounded conversations, Session settings and Compaction."
---

A Session is one bounded conversation with an Agent. Its transcript stays on the Server. A new Session does not inherit the previous transcript; Memory is what carries forward, alongside the Agent's Identity, Soul and User Notes.

A Session can choose its own model provider, model, reasoning effort and Risk tolerance. Unset Session settings follow the Agent's. Changing a Session does not rewrite the Agent's defaults.

As a Session grows, Compaction replaces older context with a summary so it can continue. The full transcript remains on disk. The Agent's `compactAtTokens` setting is a threshold, and model context limits can require earlier Compaction.

Routines can create a fresh Session for each run or continue a selected Session. Task runs are linked from the Board. [Reflection](reflection.md) reviews finished conversations to curate durable information.

Each Session belongs to one Agent. An Agent can list, search and read its own past Sessions and check their status with the `agent_tools` group; it never sees another Agent's Sessions or a Hidden Session. External Agents get the same tools over MCP, subject to the same Approvals.

See [Agent configuration](../reference/agent.md) and [Routines and Tasks](routines-and-tasks.md).

<div class="k-future">

**Future enhancements**

- **Teams**: a named group of Agents that share Artifacts; a Session can include several Team members through @mention.
- **Delegation**: an Agent hands work to another in a child Session, optionally a Hidden Session whose transcript no Agent tool can see ([plan](../plans/2026-11-07-agent-collaboration-design.md)).

</div>
