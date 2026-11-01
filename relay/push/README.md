# Kinuvo Push relay

A stateless Cloudflare Worker that forwards encrypted Notifications from any Kinuvo Server to APNs and FCM ([ADR 0020](../../docs/adr/0020-push-relay.md)). Servers POST a Web Push `aes128gcm` record (RFC 8291); the relay never sees plaintext and stores nothing.

## API

| Route | Upstream |
| --- | --- |
| `POST /v1/apns/{sandbox\|production}/{hex device token}` | APNs alert with `mutable-content`; the record is in `k` for the Notification Service Extension |
| `POST /v1/fcm/{registration token}` | FCM HTTP v1 data message `{k}` |

Requests need `Content-Encoding: aes128gcm` and a 1–3072 byte body. `TTL` sets the expiry (default 1 day, max 28 days); `Urgency: high` maps to APNs priority 10 / FCM `HIGH`.

| Status | Meaning |
| --- | --- |
| 201 | Accepted upstream |
| 400 / 404 / 405 / 413 | Bad request, route, method or body size |
| 410 | Device token is dead; the Server drops the subscription |
| 429 | Over 30 pushes per minute for this token, or upstream throttling |
| 502 | Upstream failure |
| 503 | That provider isn't configured |

## Configuration

Vars in `wrangler.toml`: `APNS_TOPIC` (iOS bundle id), `APNS_KEY_ID`, `APNS_TEAM_ID`, `FCM_PROJECT_ID`.

Secrets:

```sh
npx wrangler secret put APNS_KEY < AuthKey_XXXXXXXXXX.p8
npx wrangler secret put FCM_SERVICE_ACCOUNT < service-account.json
```

The rate limit uses the Workers Rate Limiting binding (`[[ratelimits]]`), keyed by a SHA-256 of the device token.

## Deploy

```sh
npm install
npx wrangler deploy
```

The route expects `push.kinuvo.dev` as a custom domain on the Cloudflare account.

## Local testing

```sh
npm test
```

Tests run in plain Vitest with `fetch` and the rate limiter stubbed, and throwaway EC/RSA keys.

APNs only accepts HTTP/2. Deployed Workers reach it because Cloudflare's network upgrades outbound requests to HTTP/2, but local `wrangler dev` (workerd) speaks HTTP/1.1, so APNs pushes fail locally ([workerd#4841](https://github.com/cloudflare/workerd/issues/4841)). FCM works locally.
