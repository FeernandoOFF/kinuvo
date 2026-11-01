---
title: "Push relay for APNs and FCM"
description: "A hosted, stateless Push relay forwards encrypted Notifications from any Server to APNs and FCM."
status: accepted
---

Self-hosted Servers can't call APNs or FCM for the published apps: the APNs key and FCM credentials belong to the publisher and can't ship with every Server. Polling (BGAppRefresh, WorkManager) is best effort and can be hours late, which is too slow for Approvals. So Kinuvo hosts a **Push relay** that holds those credentials and forwards Notifications from any Server, the same model as Bitwarden, Home Assistant, Mastodon apps and Matrix's Sygnal.

## Decision

- **Stateless relay on Cloudflare Workers.** It forwards `{device token, encrypted payload}` to APNs or FCM and stores nothing. Its source is public. It rate limits per device token; Servers don't register with it.
- **End-to-end encrypted payloads.** When the Device is paired, it gives the Server a push endpoint and keys. The Server encrypts each Notification (Web Push, RFC 8291), so the relay sees only an opaque blob. On iOS, a Notification Service Extension decrypts it (`mutable-content`), using shared Kotlin where the extension can load it and Swift otherwise; on Android, the app decrypts it in its FCM service.
- **One Server-side protocol.** The Server only POSTs encrypted Web Push messages to an endpoint URL. The endpoint can point at the relay (APNs, FCM), at an Android UnifiedPush distributor (bypassing the relay and Google), or at a browser's push service.
- **Alternatives stay.** The ntfy Notifier remains for users who want no dependency on the relay. Background refresh catches up on missed Notifications.

## Consequences

- If the relay is down, iOS and FCM pushes stop for every Server. Notifications are still delivered on the next background refresh or app open.
- Any Server can reach the relay; per-token rate limits bound what a misbehaving one can do to a Device.
- Notification content can't depend on the Server being reachable when the push arrives.

## Future work

- UnifiedPush distributors on Android; FCM ships first.
- Bring-your-own APNs key, for self-hosters who build their own app.
