---
title: "Sessions"
description: "Bounded conversations, Session settings and Compaction."
---

A Session is one bounded conversation with an Agent. Its transcript stays on the Server. A new Session does not inherit the previous transcript; Memory is what carries forward, alongside the Agent's Identity, Soul and User Notes.

A Session can choose its own model provider, model, reasoning effort and Risk tolerance. Unset Session settings follow the Agent's. Changing a Session does not rewrite the Agent's defaults.

As a Session grows, Compaction replaces older context with a summary so it can continue. The full transcript remains on disk. The Agent's `compactAtTokens` setting is a threshold, and model context limits can require earlier Compaction.

Routines can create a fresh Session for each run or continue a selected Session. Task runs are linked from the Board. [Reflection](reflection.md) reviews finished conversations to curate durable information.

Each Session belongs to one Agent. An Agent can list, search and read its own past Sessions and check their status with the `agent_tools` group; it never sees another Agent's Sessions or a Hidden Session. External Agents get the same tools over MCP, subject to the same Approvals.

With `delegate` an Agent hands a goal to another Agent, or to itself with a fresh context, in a new child Session on that Agent, native or External. The child starts from the goal and context alone, never the parent's transcript. By default the parent waits for the child's final answer; in the background it fetches the answers later with `delegate_wait`, and several tasks run in parallel (up to four per call). `subagents` lists and cancels the children, and stopping a Session stops its running descendants. A child's questions and Approvals appear in its own Session for you to answer. Delegation is limited in depth by `delegationDepth` in [Agent configuration](../reference/agent.md); a child at the limit gets no delegation tools. The parent's `sessions_*` tools also see the children it delegated.

A delegation can be `hidden`, for work on sensitive data: the child must run on an on-device model, its transcript is invisible to every Agent tool, it is never reflected on or notified about, and only the final answer crosses to the parent. With `review`, you see that answer and choose whether it reaches the parent. Work a Hidden Session hands on stays hidden. You still see Hidden Sessions in the app. A Hidden Session also has no web, Browser, Computer or MCP tools and can't generate images, so nothing it writes leaves the device by itself, and nobody can switch it to a cloud model. A Session running on an on-device model that delegates to a cloud Agent sends it only the goal and context it writes.

A delegated child that waits on your question or Approval for 30 minutes stops its parent's wait with a failure (as an unattended Routine does); its Session stays open for you to answer later.

See [Agent configuration](../reference/agent.md) and [Routines and Tasks](routines-and-tasks.md).

<div class="k-future">

**Future enhancements**

- **Teams**: a named group of Agents that share Artifacts; a Session can include several Team members through @mention.

</div>
