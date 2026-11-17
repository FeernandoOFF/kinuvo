---
title: "TailPocket as the private-tunnel transport"
description: "Reach a Server from any network through TailPocket pairing, bound to Kinuvo Devices, with committed prebuilt native libraries."
---

Status: Accepted. Supersedes [0039](0039-tailcat-transport.md).

## Decision

TailPocket (github.com/jt-wang/tailpocket v0.2.4, MIT) is the optional way to reach a Server from any network, alongside LAN, Tailscale Serve and public URLs. We use it only as a transport: Kinuvo authentication is unchanged, and a Device still pairs with a one-time pairing code and holds a Device token. TailPocket builds on Tailcat (pinned to v0.6.0 by TailPocket), which gives an account-free encrypted WireGuard tunnel over direct UDP or free DERP relays. The relays have no SLA.

**Pairing.** The Server's tunnel host opens a one-phone TailPocket pairing window per Kinuvo pairing code, with that code as the window's payload. The invite text (`kinuvo1:…`) is the QR or link content: `kinuvo://pair?tailcat=<invite>`, and the bare invite is accepted when pasted. The client pairs the tunnel, which returns a Link (the phone's private key and the host's secret address) and the payload. It stores the Link in secure credentials, then runs the normal Kinuvo pairing with the payload's code over a loopback forward and keeps the resulting Device token. One scan does both. Switching a saved Server to a plain URL keeps the same Device token.

**Binding and revocation.** The host records which phone key used each pairing code while it delivers the payload, before the phone can reply. When a Device pairs with that code, the Server stores the phone key on the Device. Revoking the Device revokes the phone, through the running tunnel (other phones stay connected) or, while the tunnel is off, by editing the saved state. A revoked phone can no longer reach the Server; its Device token is already invalid.

**Server host.** The shim accepts paired phones on TailPocket's host listener and proxies each to a dedicated ephemeral loopback listener, as before: it trusts no forwarded headers, rejects browser Origin requests and the browser session endpoints, and applies the existing Device authentication and rate limits. The host's State (identity and allowed phone keys, both secret) is saved at `<home>/tailcat/state.json` with mode 0600 before any change takes effect, so the private address and paired phones survive restarts. "Reset private address" creates a new identity and drops every paired phone; it needs the tunnel stopped, and a process lock prevents it while the identity is in use. Shutdown is bounded; if upstream close hangs, keep the lock until it finishes or the process exits.

**Native library.** The Go shim (`libs/tailcat/go`) exposes a versioned JSON C ABI (ABI 2) over TailPocket's host and mobile packages. TailPocket's mobile package supplies the Android network-interface fallback, so the old JNI interface getter is gone. Prebuilt binaries are committed under `libs/tailcat/prebuilt` (plain git): a macOS arm64 dylib, Android arm64-v8a and x86_64 shared objects, and an iOS device and simulator xcframework. CI and releases never need Go or the NDK. `libs/tailcat/build.sh` rebuilds them manually, only when the Go sources change, and records `STAMP`: a hash over the Go sources, go.mod, go.sum and the Go and NDK versions, plus a hash of every artifact. CI runs `libs/tailcat/stamp.sh check`, which needs no toolchain. Linux and Windows have no library, so Tailcat reports itself unavailable there.

**Bindings.** The Kotlin module `libs/tailcat` implements the SDK's `TailcatForwarder` and the Server's `TailcatServing` ports (ADR 0029): FFM on the JVM, JNI on Android and, on iOS, a Swift wrapper that hands the linked static library's entry point to the shared Kotlin code. Only app shells depend on it. Native calls run off the UI thread; concurrent clients of a saved Server share one forward while in use, and a dropped socket invalidates it so reconnect creates another.

**Client model.** `Server.Remote` keeps an explicit URL or Tailcat transport. For Tailcat, `url` is an opaque local `tailcat:<uuid>` key, never a network URL or the secret; the Link lives in the credential store (`<key>.link`) next to the Device token. Unpair retains the Link; forget deletes both. A saved Server has one transport.

**Sharing.** Embedded Servers expose independent sharing options. Desktop can toggle and reset the tunnel only when it owns the Server. Android persists independent LAN and tunnel choices and restores them with the foreground Server service. iOS can reach tunnel Servers; hosting one from the iPhone needs a network listener on iOS and is separate work.
