---
title: "Artifacts as mini apps"
description: "Each Artifact gets an origin of its own, then becomes a folder with a capability manifest and a host bridge, all inside Kinuvo."
---

Status: Accepted. Extends [ADR 0023](0023-html-surfaces-and-mcp-apps.md) and [ADR 0044](0044-artifact-page-state.md).

## Context

An HTML Artifact is one string shown as an `about:srcdoc` frame with `sandbox="allow-scripts"`. That frame has an opaque origin: no Web Storage, no WebAssembly streaming, no workers, no relative assets, and reveal.js cannot reload its own speaker view. Agents increasingly build small apps rather than pages.

## Decision

- **Origin.** The Device serves each Artifact from an origin of its own, with the bytes it already fetched from the Server. The platform decides the address; the Artifact is never on the app's origin.
  - iOS: `kinuvo-artifact://<id>/` through a `WKURLSchemeHandler`.
  - Android: `https://<id>.invalid/` through request interception; `.invalid` never resolves and, being no registrable domain, keeps cookies per Artifact.
  - Desktop: the web view cannot register schemes, so each open Artifact gets a listener of its own on `127.0.0.1`, served at `http://127.0.0.1:<port>/<token>/`; its port is its origin. Requests need the random token and the exact `Host`, which keeps other local software and DNS rebinding out. It is an IP address because macOS app transport security rejects `*.localhost` names in a packaged app.
  - Web clients keep `srcdoc`: a page the app itself serves shares its origin, so there is no isolated alternative.
- **Policy** is sent as `Content-Security-Policy` response headers with the defaults an Artifact had (https scripts, styles, fonts and images; no connections; no frames), and also stays in the page head for web views that ignore response headers. Navigation away from the Artifact origin is blocked where the web view reports it, and a link the user taps opens in the system browser. There is no `window.open`.
- **Storage.** Each origin has real `localStorage`, `sessionStorage` and IndexedDB, discarded with the web view. Durable state belongs to the Server (ADR 0044), not to the Device. Pages that stay opaque (the visual editor, design artboards, the web client) keep the in-memory storage shim.
- **Bundles.** An Artifact becomes a folder: `index.html`, assets, `.wasm`, and `manifest.json`. The origin serves files by path.
- **Capabilities.** The manifest asks for `network` origins, `popups`, `storage` and `tools`. Everything is denied by default; the owner grants each Artifact its own capabilities in a sheet, with a "grant all" switch.
- **Bridge.** Pages talk to the host with the MCP Apps protocol plus Kinuvo capabilities, with state synced through the Server; ADR 0044's channel is its first piece. Typed Artifacts build on it.
- **Trust.** Mini apps stay inside Kinuvo: never published, never shared publicly. Grants are per Artifact and the Server owns their state.
- **Order:** origin, bundles, capabilities, bridge, typed Artifacts.

## Consequences

- Pages get storage, workers, WebAssembly and relative assets; reveal.js runs unmodified.
- The desktop opens a loopback port per Artifact shown, closed with its view, bound to `127.0.0.1` and refused without its token. Bundles there must use relative URLs, as the token is the first path segment.
- The desktop web library exposes no hook for frame navigation, so a page there can still navigate its own frame away; blocking it is future work.
- Platform web views serve and block; shared code owns the policy, paths and MIME types.
