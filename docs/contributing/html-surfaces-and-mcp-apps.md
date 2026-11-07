---
title: "HTML Surface and MCP App contracts"
description: "Contributor reference for html surface and mcp app contracts."
---

Agents can show HTML Surfaces in the chat when native A2UI components are not enough, and MCP servers show their own UIs as MCP Apps (SEP-1865, `2026-01-26`). Both use one host: the MCP Apps protocol is the only bridge between web content and Kinuvo. An HTML Surface is an MCP App that Kinuvo serves itself; there is no separate Kinuvo JavaScript API.

## Host protocol

Web content runs in a sandboxed iframe (`allow-scripts`, no same origin) inside a trusted shell document. The shell relays JSON-RPC `postMessage` traffic between the iframe and the native bridge and applies the Content Security Policy: no network by default. An MCP App's resource may widen it with `_meta.ui.csp`: `connectDomains` become `connect-src`, `resourceDomains` become script, style, image, font and media sources. Only https origins (optionally `*.` subdomains) are accepted, at most 32 each.

The host logic lives in `shared/app` as a platform-neutral state machine. Platform web views only pass strings in and out. The host answers `ui/initialize` with the host context (theme, display mode, container size, locale, platform), waits for `ui/notifications/initialized`, then sends `ui/notifications/tool-input` and `ui/notifications/tool-result`. It handles:

- `ui/notifications/size-changed`: sets the inline height, capped in the chat; content that grows further is shown in fullscreen.
- `ui/message`: sends a user message to the Session, like a Surface action.
- `ui/open-link`: opens the URL with the platform opener.
- `ui/request-display-mode`: inline or fullscreen.
- `tools/call`: MCP Apps only, answered asynchronously once the Server replies; HTML Surfaces get method-not-found.

## HTML Surfaces

`show_html` takes an HTML document or fragment, optional title and optional JSON data object, delivered as `params.arguments` in the tool-input notification. Arguments are capped at 512 KiB of UTF-8 JSON and HTML must be non-empty. The tool is Low Risk and follows `show_ui` Device catalog and model gating. Like A2UI Surfaces, each call creates an independent Surface that stays in the chat.

## MCP Apps

The MCP client advertises the `io.modelcontextprotocol/ui` extension with the `text/html;profile=mcp-app` MIME type. When a listed tool declares `_meta.ui.resourceUri`, the Server reads that `ui://` resource, requires the MIME type, caps the HTML at 512 KiB and caches it with its `_meta.ui` per Agent, keyed by content hash. Tools whose `_meta.ui.visibility` excludes `model` are not offered to the model; those including `app` are callable by that server's apps.

The tool result entry records an `McpAppRef` (MCP server instance, resource URI, hash) and the full `CallToolResult`, which Devices pass to the app as `ui/notifications/tool-result`; the model still sees only the text output. Devices fetch the HTML with `apps.resource` once per app reference and connection. After a restart the Server reconnects to the MCP server and reads the resource again; a changed hash requires a fresh tool call. The hash covers HTML and UI metadata.

An app's `tools/call` goes through `apps.callTool`. The Server resolves the app from the stored tool result of that Session's call, never from the Device's request, and only runs that MCP server's app-visible tools. The Agent's Approval policy applies as in a run (Risk, Trust rules, Session trust): calls that need an Approval show the normal Approval card in that Session, outside any run, and wait up to 10 minutes; denied, unanswered or blocked calls fail with an error the app can show. Allow for this Session and Always allow are saved as in a run. App calls are not recorded in the Session. MCP App tool results tell the model that the user sees them in the tool's app. `resources/read`, `ui/update-model-context` and other app requests are not supported and return method-not-found with an explicit unsupported-method message.

## Rendering

| Platform | Engine |
| --- | --- |
| macOS desktop | System WKWebView, rendered offscreen into Compose images |
| Windows / Linux desktop | Placeholder card |
| Android | System WebView (`AndroidView`) |
| iOS | WKWebView (`UIKitView`) |
| Web | iframe |

Pages sit inline in the chat without a frame, on the chat's background. The host passes Kinuvo's light or dark palette and the Agent's accent as MCP Apps style variables (`--color-background-primary` is the chat background) and applies them as default page styles. Web views don't zoom.

A web view is live only while its chat row is composed and intersects the clipped window bounds, with a cap of three live inline views per Session; others show a placeholder that loads them on tap. Inline height is capped at 560dp and scrolling is disabled in the app document. The expand control recreates a scrolling document, up to 560dp high, in the existing Popover host; this is an expanded sheet rather than an OS fullscreen window. The last measured height is kept per tool call to avoid layout jumps when a view is recreated, and recreated apps receive their input and result again.

On phones, focusing a field in a page hides the composer under the keyboard; it slides back when the keyboard closes. On iOS, Compose's blur cannot sample the native view.

macOS uses `libKinuvoWeb.dylib`, built from `shared/ui/native/macos/OffscreenWeb.swift` by the desktop launcher and packager and loaded in-process with Java FFM, like speech. It adds no browser runtime. WKWebView uses a non-persistent data store in a borderless window placed offscreen with mouse interaction disabled; only public APIs are used. Snapshots at twice the point size are copied into Compose images, so pages take part in Compose clipping, overlays and blur. Captures follow loads, resizes, input, DOM mutations, animation callbacks and a focused field's caret, at most one at a time and 30 per second; static pages capture nothing while idle. Mouse, keyboard, modifiers, typed text and focus are forwarded to AppKit, with native key equivalents for copy and paste. `KINUVO_TEST_NATIVE_HTML="$PWD/build" mise run kotlin -- test -m ui -p jvm --include-classes=kinuvo.ui.DesktopHtmlSurfaceTest`, after `mise run exec -- scripts/build-mac-web.sh`, checks the bridge, input and image conversion against the real helper.

Android exposes the bridge only to the trusted shell origin with WebMessageListener; unsupported WebView versions have no bridge. iOS uses a main-frame-only WKScriptMessageHandler and a non-persistent data store. Web uses an additional opaque-origin iframe shell with `allow-scripts` only. This deliberately stricter inline shell does not implement the MCP Apps sandbox-proxy resource handshake. All views are disposed with their composition. HTML Surfaces load no external scripts, styles, images or network connections.

Future work: input methods (IME) and dictation on macOS, verified animation while the window is unfocused, Windows and Linux rendering, `ui/update-model-context`, picture-in-picture, app state persistence, a fully native iOS rendering path.
