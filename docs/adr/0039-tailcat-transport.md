---
title: "Optional Tailcat transport"
description: "Reach a Server through a secret peer-to-peer address while retaining Device authentication."
---

Status: Accepted

## Decision

Tailcat is an optional way to reach a Server alongside LAN, Tailscale Serve and public URLs. It establishes an encrypted WireGuard tunnel without an account, using direct UDP where possible and its default DERP relays otherwise. The free relays have no SLA.

Use the tagged Go module github.com/tailscale/tailcat v0.7.0 behind our versioned C ABI, built as a shared library. The unreleased libtailcat ABI is not a dependency. This tag requires Go 1.27.1 or newer. Server and clients must pin the same Tailcat version; protocol compatibility is not assumed across upgrades.

The shim accepts TCP on virtual port 8787 and proxies it to a dedicated ephemeral loopback listener. That listener shares the Server runtime but trusts no forwarded headers, rejects browser Origin requests and the browser session endpoints, and applies the existing Device authentication and rate limits. All tunnel peers share one source limit. The main listener and its proxy settings are independent.

Persist the node private key, WireGuard pre-shared key and full relay region under the Server home's tailcat directory, with a 0600 key file. Saving the region as well as the keys keeps the address stable. The CLI reset command requires the Server to stop and deletes that identity; the next start generates a new one. An owning desktop app stops its tunnel before resetting and restarts it when previously enabled. A process lock prevents reset while the identity is in use. Shutdown is bounded; if upstream close hangs, keep the lock until close finishes or the process exits.

The address is a bearer secret: knowing it grants network reachability, never Device or management access. A pairing code or Device token is still required. Show it only in pairing information, including `kinuvo pair`; never log it.

The JVM FFM and Android JNI binding lives in `native/tailcat` beside the Go code. It implements the SDK's `TailcatForwarder` and the Server's `TailcatServing` ports, and only app shells wire it in (ADR 0029). Platform SDK forwarders provide loopback WebSocket endpoints. Concurrent clients of a saved Server share one forward while in use. Each connection attempt owns a lease; the last close releases the forward. Dropped sockets invalidate it, so reconnect creates another because upstream tailcat issue 159 can leave old forwards hanging after a Server restart. Native calls run off the UI thread. Android uses the same ABI through JNI, packaged in one library per ABI without a VPN or TUN. 

iOS is client-only. It uses the pure-Swift port github.com/slopus/tailcat-swift (BSD-3), pinned to one exact revision in the Xcode project because it is a third-party reimplementation without releases or a wire-format promise, not Tailscale's code. Interoperability with the Go v0.7.0 Servers is verified by pairing, reconnecting and restarting a Server, and must be rechecked when either side's pin changes. A Swift forwarder behind a small `NativeTailcat` bridge implements `TailcatForwarder` with a loopback-only listener per tunnel, bounded to 128 connections like the Go shim. Only the app target links it, never the extensions. The tunnel is not kept alive in the background: it dies with the suspended app, and the dropped socket invalidates the forward so reconnect builds a new one. iOS cannot host a Tailcat Server. Apple cinterop forwarders remain unsupported.

Keep `Server.Remote` with an explicit URL or Tailcat transport. For Tailcat, its `url` is an opaque local `tailcat:<uuid>` key, never a network URL or the secret. This preserves Server selection, cache and Device-tool grant identities without spreading another Server subtype through feature code. Persist only that key, alias, transport and Server identity in preferences; keep the address in the platform credential store alongside the Device token. Unpair retains the address; forget deletes both. Pairing a reset address merges by Server identity and retains the previous key and alias. A saved Server has one transport.

Embedded Servers expose independent sharing options, each with unavailable, off, starting, on or error state. Desktop can toggle and reset Tailcat only when it owns the Server, with the enabled preference stored per Server home and defaulting off. Joining a CLI or login-service process makes the control unavailable. Android persists independent LAN and Tailcat choices, defaulting off, and restores them with the foreground Server service. Its Tailcat identity lives under filesDir/Kinuvo/tailcat; stopping sharing or the service closes the tunnel and its remote-only loopback listener. Reset stops the tunnel before deleting its identity and restarts it when enabled. Missing Android native libraries leave Tailcat unavailable. UI controls are separate work.
