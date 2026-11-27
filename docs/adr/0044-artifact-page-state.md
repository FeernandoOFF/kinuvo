---
title: "Artifact page state"
description: "Pages of an Artifact share a small JSON state through the Server, in memory, so Devices showing one page stay in step."
---

Status: Accepted

## Context

An HTML Artifact runs in an opaque-origin sandbox with no network (ADR 0023), so a page cannot talk to other Devices itself: reveal.js multiplexing, which needs a socket, cannot work. Presenting a deck from a phone needs the Devices showing it to agree on the current slide, and mini apps in general will need state their pages share.

## Decision

- The Server keeps one small JSON value per Artifact (`ArtifactStates`, up to 16 KB) and a set of watching connections. `artifact.state.watch` returns the latest value and subscribes the connection, `artifact.state.set` replaces it, and every other watcher gets an `artifact.state` event. The setter gets none, and a connection can set only what it watches. The three methods are ordered, so quick changes arrive in the order they were made.
- The channel knows nothing about slides. The state is the page's to define; the sandbox is unchanged.
- State lives in memory only: a Server restart forgets it, and deleting the Artifact clears it. A watcher is dropped when its connection closes.
- Pages reach the app through the existing web view message path (page to app) and `kinuvoReceive` (app to page). The app, not the page, names the Artifact, so a page can only change its own state, and the app parses what a page sends and ignores anything else.
- A reveal.js adapter is injected into the viewed page and does nothing unless `window.Reveal` exists. It reports the slide indices, the current slide's speaker notes and the next slide, and applies positions it is sent. A position it applied is not reported again.

## Consequences

Devices showing a deck follow each other both ways, and one can drive the others with notes and a next-slide preview. Every Device needs to load the deck itself (reveal.js comes from its CDN). Web clients cannot pass page messages to the app, so they neither report nor follow. Persisted state and richer page APIs are future work.
