---
title: "Sessions"
description: "Bounded conversations, Session settings and Compaction."
---

A Session is one bounded conversation with an Agent. Its transcript stays on the Server. A new Session does not inherit the previous transcript; Memory is what carries forward, alongside the Agent's Identity, Soul and User Notes.

A Session can choose its own model provider, model, reasoning effort and Risk tolerance. Unset Session settings follow the Agent's. Changing a Session does not rewrite the Agent's defaults.

As a Session grows, Compaction replaces older context with a summary so it can continue. The full transcript remains on disk. The Agent's `compactAtTokens` setting is a threshold, and model context limits can require earlier Compaction.

Routines can create a fresh Session for each run or continue a selected Session. Task runs are linked from the Board. [Reflection](reflection.md) reviews finished conversations to curate durable information.

Each Session belongs to one Agent.

See [Agent configuration](../reference/agent.md) and [Routines and Tasks](routines-and-tasks.md).

<div class="k-future">

**Future enhancements**

- **Rooms**: a lasting conversation space defined by its members, you and one or more Agents.

</div>
