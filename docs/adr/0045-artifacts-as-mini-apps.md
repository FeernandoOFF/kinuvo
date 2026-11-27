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
- **Policy** is sent as `Content-Security-Policy` response headers with the defaults an Artifact had (https scripts, styles, fonts and images; no connections; no frames), and also stays in the page head for web views that ignore response headers. Navigation away from the Artifact origin is blocked where the web view reports it, and a link the user taps opens in the system browser. There is no `window.open` unless the owner grants `popups`.
- **Storage.** Each origin has real `localStorage`, `sessionStorage` and IndexedDB, discarded with the web view. Durable state belongs to the Server (ADR 0044), not to the Device. Pages that stay opaque (the visual editor, design artboards, the web client) keep the in-memory storage shim.
- **Bundles.** An HTML Artifact can be a folder: an entry page, assets, `.wasm` and an optional `manifest.json`. The origin serves files by path; a single file stays a single file and reads as a one-file folder.
  - **Storage.** `~/.kinuvo/artifacts/<id>.meta.json` as before; a folder keeps its files in `<id>/` instead of `<id>.html`, and its `ArtifactInfo.bundle` is true. Nothing is migrated: an Agent writing a file into a single-file Artifact moves its content to `<id>/index.html`.
  - **Files.** Relative paths of at most 200 characters and 8 folders, with no `..`, absolute, empty, hidden or backslash segments, and no link that leaves the folder. Only listed extensions are kept (web files, images, fonts, audio, video, `.wasm`, `.sqlite`, `.bin`), each served as its own MIME type. At most 10 MB a file, 50 MB and 256 files an Artifact.
  - **Manifest.** `{"entry": "index.html", "title"?, "capabilities"?: {"network": [origin], "popups": bool, "storage": bool, "tools": [name]}}`. It is parsed whenever written and rejected if invalid or its entry isn't an `.html` file; `entry` picks the page. `capabilities` only asks; see Capabilities.
  - **Protocol.** `artifact.get` returns the entry page as `content` (so older Devices still show it), `artifact.files` lists path, size, MIME type and SHA-256, and `artifact.file` returns a file in chunks of up to 1 MB. Devices fetch a file when a page first asks for it and keep it while the Artifact is shown.
  - **Policy.** Pages at their own origin may also load and fetch their own files: `'self'` for scripts, styles, images, fonts, media and `connect-src`, plus `'wasm-unsafe-eval'`. `'self'` is the Artifact's own origin, so it opens no network; opaque pages (the web client, visual editor and design artboards) keep the old policy.
- **Capabilities.** The manifest asks for `network` origins, `popups`, `storage` and `tools`. Everything is denied by default; the owner grants each Artifact its own capabilities in a Permissions sheet in the viewer's menu, with an "allow everything it asks for" switch.
  - **Grants** live on the Server beside the Artifact (`<id>.grants.json`): the capabilities, when, and from which Device. What applies is the **effective** set, the requests that are also granted. "Allow everything" copies the current requests, so it never grants an origin the manifest does not list, and a bare `*` is not a valid request. When the Agent edits the manifest, grants it no longer asks for are dropped, so asking again needs a new grant; nothing is ever granted for the owner. Agents have no way to grant: the Artifact tool only writes the manifest. Deleting the Artifact deletes its grants.
  - **Protocol.** `artifact.permissions` (any Device) returns requested, granted and effective; `artifact.grants.change` (management Devices only) takes `all`, then `grant` and `revoke` sets, refusing a grant the manifest does not ask for. A change is announced with `artifact.changed`, so every Device reloads the page under the new policy. A Server without these methods leaves Devices enforcing nothing.
  - **Network.** Origins are `https://host[:port]` or `wss://host[:port]`, with a wildcard only as a leading `*.` label under at least two labels; `http:`, `ws:`, localhost, loopback addresses, paths and a lone `*` are refused when the manifest is written, and again before a Device builds a policy. Cleartext and loopback stay out because the page could reach services on the Device itself or be read on the way. Granted origins join `connect-src` (fetch, XHR, WebSocket, EventSource) and, for `https`, `media-src`. Scripts, styles, images and fonts already load from any `https` host, so nothing else widens. Every platform enforces it through the same policy, in the response header and the page's meta tag; CORS is the remote server's to allow.
  - **Pop-ups.** A granted page's frame gets `allow-popups` and may embed its own pages (`frame-src 'self'`), which reveal.js's presenter view needs. iOS answers `createWebView` and Android `onCreateWindow` by the same rule: a blank window or one of the Artifact's own pages opens in an app window that keeps its opener, so the page can fill it; `http`, `https` and `mailto` links open in the system browser; anything else is dropped. Those windows are held to the Artifact origin like the page. The desktop web view reports no new-window requests and the web client has only a sandboxed frame, so pop-ups stay blocked there even when granted.
  - **Storage.** Reserved: the grant is kept, shown as "coming soon" and does nothing. Web storage is still discarded with the view on every platform, as the desktop origin changes with each opening and cannot persist; durable state belongs to the Server (ADR 0044).
  - **Tools.** Reserved for the bridge: stored and shown, not wired.
- **Bridge.** Pages talk to the host with the MCP Apps protocol plus Kinuvo capabilities, with state synced through the Server; ADR 0044's channel is its first piece. Typed Artifacts build on it.
- **Trust.** Mini apps stay inside Kinuvo: never published, never shared publicly. Grants are per Artifact and the Server owns their state.
- **Order:** origin, bundles, capabilities (this), bridge, typed Artifacts.

## Consequences

- Folders have no "Open in browser" and no visual editor yet: their pages need the Artifact origin, and the entry page is edited as source. A single-file export of a folder is future work.
- The web client shows a folder's entry page only, as it has no origin to serve files from.
- Without a grant a page still loads scripts, styles, images and fonts from any `https` host, and a request URL can carry data out. Network grants add connections and media, not that channel; closing it is future work.

- Pages get storage, workers, WebAssembly and relative assets; reveal.js runs unmodified.
- The desktop opens a loopback port per Artifact shown, closed with its view, bound to `127.0.0.1` and refused without its token. Bundles there must use relative URLs, as the token is the first path segment.
- The desktop web library exposes no hook for frame navigation, so a page there can still navigate its own frame away; blocking it is future work.
- Platform web views serve and block; shared code owns the policy, paths and MIME types.
