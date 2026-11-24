---
title: "A Connect interaction keeps its run alive while unwatched or backgrounded"
description: "Exempt Connect from the stop-when-nobody's-watching and background-budget-expiry policies, since signing in needs leaving the app."
---

Status: Accepted

## Context

A `foregroundOnly` host (the phone's embedded Server) stops a run that's waiting on an Approval or a question once no client is watching it, and a host that keeps runs going in the background (ADR on continued processing) stops every run once its background time budget expires. Both exist so a run that needs the user looking at this screen doesn't sit there burning a Device's background allowance for nothing, and so the app isn't caught mid-stream when iOS suspends it.

An `mcp_login` Connect card is a `PendingInteraction` like an Approval or a question, so it was swept into the same policy. But signing in to an MCP server inherently means leaving the app (an in-app browser sheet, or the system browser), which looks exactly like "nobody's watching" or "the app went to the background." The run was being stopped the moment the user left to sign in, discarding the tool call before they could finish.

## Decision

A run waiting only on a `PendingInteraction.Connect` is exempt from both policies: `ServerRuntime`'s stop-when-unwatched check and `stopRuns()` (used when an iOS/Android host's background budget runs out) skip any session whose pending interaction is a Connect. The suspended coroutine costs nothing while it waits — there's no streaming or compute to interrupt — so there's no reason to tear it down defensively. It keeps waiting, bounded by the MCP sign-in's own ten-minute timeout, until the user returns and answers it (or it expires and they can retry or skip).

This doesn't fight the cases an app process genuinely can't survive (the OS killing it outright rather than calling our own expiration handling); there, the sign-in itself still finishes server-side independently of the run (the OAuth tokens are stored before the interaction is answered), only the "tell the agent" step is lost with the run.

## Consequences

Accepted cost: a Connect card left open indefinitely (the user never returns) holds its session's run suspended, and hence "active" for up to the sign-in's own timeout, rather than being cleaned up immediately like other pending interactions.
