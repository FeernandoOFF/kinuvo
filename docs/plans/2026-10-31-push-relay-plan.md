---
title: "Push relay implementation plan"
---

Implements [ADR 0020](../adr/0020-push-relay.md). FCM and APNs first; UnifiedPush later.

## 1. Relay (`relay/push/`, Cloudflare Worker, TypeScript)

- `POST /v1/apns/{env}/{token}` and `POST /v1/fcm/{token}`, where `env` is `sandbox` or `production` (debug builds use sandbox). The body is the Web Push `aes128gcm` record, at most 3 KiB so it fits APNs and FCM's 4 KiB limit once base64-encoded.
- **APNs:** an ES256 provider JWT, cached ~50 min, signed with WebCrypto. Push type `alert`, `mutable-content: 1`, fallback alert "New activity", record in a custom `k` field. Urgency maps to `apns-priority`.
- **FCM:** HTTP v1 data message (`k`, `urgent`). The OAuth token comes from a service-account JWT (RS256 via WebCrypto), cached.
- **Rate limit:** the Workers Rate Limiting binding, keyed by device token, e.g. 30/min. No storage.
- **Errors:** APNs `410`/`BadDeviceToken` and FCM `UNREGISTERED` → `410` to the Server, which then drops the subscription.
- Secrets go in `wrangler secret`. Tests use `vitest` + Miniflare with APNs/FCM mocked.

## 2. Protocol (`shared/protocol`)

- `PushSubscription(endpoint, p256dh, auth)`: the Device's ECDH P-256 public key and 16-byte auth secret, base64url.
- RPC `push.subscribe(subscription?)`: authenticated per Device; null unsubscribes. `ServerCapabilities.push` tells clients that an older Server can't take one.

## 3. Server (`harness/server/runtime`)

- `PairedDevice.push: PushSubscription?`, saved by `DeviceAuth.setPush(token, …)`. Revoking a Device drops it.
- `WebPush` (jvmAndAndroid): RFC 8291 encryption with an ephemeral ECDH key, HKDF and AES-128-GCM, using JCA. POST with `Content-Encoding: aes128gcm`, `TTL` and `Urgency`. No VAPID for the relay; add it for browser endpoints later.
- `Notifications.quietly` sends to the configured Notifier (ntfy) **and** every subscribed Device, each failing independently. On `404`/`410` the subscription is cleared.
- The payload is JSON `{title, body, agentId, sessionId, urgent}`, with the body clipped to fit.

## 4. Android (`apps/android`, `shared/ui` src@android)

- `firebase-messaging` dependency. `FirebaseApp.initializeApp` with explicit `FirebaseOptions`, because the Kotlin Toolchain has no google-services plugin. Options come from build settings, not a checked-in JSON.
- The Device keeps its ECDH key pair and auth secret in Android Keystore-backed storage, one pair per Device install.
- `KinuvoMessagingService.onNewToken` re-subscribes on every connected Server. `onMessageReceived` decrypts and posts a local notification that deep-links to the Session.
- The decrypt code lives in `shared/app` (common, using `cryptography-kotlin`), so iOS can reuse it if size allows.
- Asks for `POST_NOTIFICATIONS` (Android 13+) the first time pushes are enabled.

## 5. iOS (`apps/ios`)

- `aps-environment` entitlement, `registerForRemoteNotifications`. Subscribes with the endpoint `…/apns/{env}/{hex token}`.
- **Notification Service Extension target:** reads `k`, decrypts and replaces the title and body. It needs the private key and auth secret through a shared App Group Keychain.
- **Decrypt in Swift with CryptoKit** (`P256.KeyAgreement`, `HKDF`, `AES.GCM`), about 80 lines. A Kotlin framework in the extension would be the shared `shared/app` framework, which is far over the extension's ~24 MB memory limit. A dedicated small KMP module is possible but adds a second framework build for little gain. Swift is the default unless a slim module turns out cheap.
- Tapping a notification opens the Session through the existing deep links.

## 6. Catch-up

Opening the app already reconnects and shows waiting Approvals. BGAppRefresh and WorkManager stay best effort. No new polling.

## Tests

- Relay: Miniflare tests for routing, rate limit and error mapping.
- Server: `WebPush` round-trip against RFC 8291's test vector; Notifications fan-out; clean-up on 410.
- `shared/app`: decrypting the RFC 8291 vector.
- iOS: an XCTest for Swift decrypt against the same vector.
- E2E: a Maestro flow with a mocked relay that asserts a local notification shows. Real APNs/FCM are verified by hand.

## Setup you need to do

- A Cloudflare account with the relay's domain (`push.kinuvo.feernandooff.com`).
- An Apple APNs auth key (`.p8`, key ID, team ID), an App Group, and an NSE App ID.
- A Firebase project, its Android app config, and a service-account JSON.
