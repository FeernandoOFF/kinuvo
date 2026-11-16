---
title: "Optional Tailcat transport"
description: "Reach a Server through a secret peer-to-peer address while retaining Device authentication."
---

Status: Accepted

## Decision

Tailcat is an optional way to reach a Server alongside LAN, Tailscale Serve and public URLs. It establishes an encrypted WireGuard tunnel without an account, using direct UDP where possible and its default DERP relays otherwise. The free relays have no SLA.

Use the tagged Go module github.com/tailscale/tailcat v0.7.0 behind our versioned C ABI, built as a shared library. The unreleased libtailcat ABI is not a dependency. This tag requires Go 1.27.1 or newer. Server and future clients must pin the same Tailcat version; protocol compatibility is not assumed across upgrades.

The shim accepts TCP on virtual port 8787 and proxies it to a dedicated ephemeral loopback listener. That listener shares the Server runtime but trusts no forwarded headers, rejects browser Origin requests and the browser session endpoints, and applies the existing Device authentication and rate limits. All tunnel peers share one source limit. The main listener and its proxy settings are independent.

Persist the node private key, WireGuard pre-shared key and full relay region under the Server home's tailcat directory, with a 0600 key file. Saving the region as well as the keys keeps the address stable. The reset command requires the Server to stop and deletes that identity; the next start generates a new one. A process lock prevents reset while the identity is in use. Shutdown is bounded; if upstream close hangs, keep the lock until close finishes or the process exits.

The address is a bearer secret: knowing it grants network reachability, never Device or management access. A pairing code or Device token is still required. Print it with the Server's connection information and include it in pairing information; handle logs and pairing information as secrets.

macOS and Linux come first. The FFM binding temporarily lives in server/jvm, which has no dependency-edge changes. Before the JVM client uses it, extract a neutral transport module allowed by ADR 0029; client modules must not depend on server/jvm. Android JNI and Apple client bindings, connection selection and UI controls are future work.
