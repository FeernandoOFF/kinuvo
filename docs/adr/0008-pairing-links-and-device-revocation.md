---
title: "Pairing links prefill; paired Devices manage access"
description: "Kinuvo pairing links prefill; paired Devices manage access."
---

QR codes hold `kinuvo://pair?server=<encoded address>&code=<code>` for the phone's camera to open the installed app, or `http(s)://<server>/#pair=<code>` for its web app. Links only fill the Connect form; the user taps Pair. The browser removes the fragment immediately, keeping the code out of HTTP requests and subsequent browser history. We use the system camera to avoid in-app camera dependencies and permissions; desktop URL-scheme registration is out of scope.

Every paired Device can fetch or rotate the ten-minute, single-use code, list paired Devices, and revoke any of them, including itself. Revocation removes the persisted token hash and closes all of that Device's connections after acknowledging the caller. In-memory desktop and CLI session tokens cannot be listed or revoked. This is a single-user trust model: another Device's approval is unnecessary. Stronger credentials, rate limits, and token rotation remain future work.

Aliases belong to the Device. Saved remote Servers merge by the Server's stable identity when connected through another address, keeping the alias. The sharing panel remembers its selected address per Server. Forget attempts to revoke this Device before removing the saved Server; when the Server cannot be reached, it removes the local entry and reports that server-side pairing may remain. A revoked Device retains the Server's alias and address to pair again. The embedded Server uses the same RPCs and appears as “This device”; a foreground on-device Server without a listener cannot pair other Devices.
