---
title: "Android Browser"
---

The Android Server offers Browser when the installed WebView supports AndroidX
`MULTI_PROFILE`. Unsupported engines do not advertise Browser or silently fall
back to a shared profile. Enable Browser for an Agent in **Tools & MCP → Built-in
tools**, then open its Session's Browser globe.

## Tools and ownership

Android uses the same Browser plugin, tool identities, Risk levels, Approvals,
Trust and Deny rules as JVM Servers. Available tools are tabs, open, capture,
navigate, click, type, scroll, wait and close. A capture returns text, semantic
refs, a revision and an image. Ref-based actions require the exact current
capture and control generation; DOM changes invalidate refs. JavaScript failures
are returned as tool errors. Unknown action outcomes are not retried.

There are no additional password, OTP or payment-field refusals. These fields
follow the existing Agent policy and normal browser element behavior. User input
through Takeover is separate from Agent tool calls. No password-manager injection
or arbitrary JavaScript tool is provided.

Tabs belong to an Agent and are shared by all of its Sessions. Each Agent uses a separate native WebView
profile for cookies and local storage, shared by that Agent's Sessions and
retained across process restarts. Disabling Browser closes that Agent's tabs;
deleting a Session leaves its Agent’s tabs open; deleting an Agent removes its tabs and profile.
Android limits the Server to eight open tabs. Tabs and their control claims are
in memory: process restart closes them rather than replaying navigation or input.

## Hosting and Live view

Each tab is attached to a private 1280×800 virtual display owned by the Server.
It renders only Kinuvo's content and neither mirrors the phone screen nor needs
overlay or MediaProjection permission. File/content access and WebView debugging
are disabled. HTTP(S), including private sites, and about:blank are accepted;
explicit navigation with embedded URL credentials or other schemes is rejected.

The existing Screen protocol supplies management authorization, bounded latest
frames, draw acknowledgements and frame-bound user input. A tab shares one
capture producer across viewers at up to three frames per second, stopping that
producer when the last viewer leaves. Image delivery is bounded independently
from tool responses and Approvals. Native rendering uses Main; tab actions and
semantic captures are serialized. Closing a tab or the Server releases its native
WebView, presentation, virtual display and image reader.

Takeover supports tap, text, Enter, Backspace, Tab, Escape, arrow keys and scroll.
It drains in-flight Agent work and blocks new Agent operations. Disconnect leaves
control suspended. The user can recover with **Take over here**, then explicitly
hand back. Control changes invalidate Agent refs and pending Approval bindings.

## Limits

Semantic controls in cross-origin iframes and shadow roots, file transfers,
separate popup windows and native dialogs are not supported. Use ordinary
Takeover for page controls the semantic tools cannot operate. Some sites and
sign-in providers restrict embedded WebViews; this is not desktop Chromium.

Screen-off/background loading, actions and pixel capture passed short Samsung
Android 16 checks. Secure keyguard, long idle periods, Doze, battery saver,
memory pressure and other WebView/device versions remain unverified. Browser
requests no wake lock and does not promise unattended locked-screen reliability.
