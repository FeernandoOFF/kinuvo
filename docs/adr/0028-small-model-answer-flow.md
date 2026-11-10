---
title: "Small-model answer flow"
description: "Reserve an answer when a run reaches its request or tool-call limit."
---

Status: Accepted

## Decision

Chats and Routines make one final request without tools when their step limit is reached. The prompt asks for an answer from the available results and a brief account of anything missing. Failure of that request retains the unfinished-work error. Reflection is unchanged.

Micro executes at most two tool calls per turn; Small executes four. Internal bookkeeping and synthetic duplicate or skipped results do not consume this budget. Once spent, the next request asks for an answer without tools. Mid and Frontier have no tool-call budget.

Micro leaves out web fetch, whose pages fill its context and whose URLs it tends to guess; a scope can still name it. Micro sees only the latest turn plus a note of the four user questions before it, not earlier replies, which small models copy. Blocked repeat searches stay in the Session but are left out of the prompt, so the model answers from the real results.

Registered searches reject reworded queries whose normalized word sets have Jaccard overlap of at least 0.6. This returns guidance without triggering the failed-call repeat abort. Micro and Small receive a prompt-only answer nudge after results, and shorter web-search snippets and URLs without dates. Larger tiers retain full search output.

This keeps tool choice with the model. Deterministic search and summarise remains deferred.

Micro and Small read the first search result with a six-second timeout and keep up to 1,200 characters of query-relevant passages. Failed reads leave search results intact. Search uses the Server time zone for country and city; Firecrawl Country and Location settings override those defaults. Unknown zones omit the region. Result limits still trim only the model prompt.
