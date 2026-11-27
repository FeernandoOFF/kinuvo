---
title: "Artifact permissions"
description: "Choose which sites a mini app your Agent made may reach, and whether it may open windows."
---

An Artifact your Agent builds as a folder can ask for more than its own files. It lists what it wants in `manifest.json`; nothing is allowed until you say so.

## What an Artifact can ask for

- **Network.** Servers the page may talk to, for example `https://api.example.com` or `wss://live.example.com`. A page using socket.io needs both the `https` and the `wss` address of its server. Only encrypted addresses are accepted, and a wildcard only as a leading `*.` such as `wss://*.example.com`. The page's own files never need permission.
- **Pop-up windows.** Lets the page open a window, for example reveal.js's presenter view. A window onto the Artifact's own pages opens inside the app; a link to another site opens in your browser.
- **Storage and tools.** Reserved for later. You can allow them now and your choice is kept, but they do nothing yet.

## Allow or refuse

1. Open the Artifact and choose **Permissions** from its **⋯** menu. A dot on the menu button means something it asked for has not been answered.
2. Switch on what you are happy with, or switch on **Allow everything it asks for**. That allows exactly what the manifest lists now, never more.

Your choice applies to that Artifact only, on every device, and the page reloads under the new rules. Only a management device can change it. Artifacts are made by your Agent: network access lets the page send its own content to the sites you allow, so allow only servers you trust.

If your Agent later changes what the Artifact asks for, the new request waits for your answer; anything it stopped asking for is forgotten. Deleting the Artifact deletes its permissions. The Agent can ask but never allow.

## Where it works

| | Network | Pop-ups |
|---|---|---|
| iPhone, iPad | Yes | Yes |
| Android | Yes | Yes |
| Desktop app | Yes | Not yet |
| Web app | Yes | Not yet |

A server the page reaches must also accept the page: socket.io and `fetch` need the server to send the usual CORS headers for the page's origin.
