---
title: "Protocol version negotiation"
description: "Server and Device each speak a range of protocol versions and connect on the newest one both speak."
---

# Protocol version negotiation

## Decision

- `ProtocolVersion` has `Minimum` and `Current`. Each build speaks every version in that range. `Current` changes only for breaking wire changes; additive ones are defaulted fields or `ServerCapabilities` entries.
- `connect` sends `protocolVersion` (the Device's `Current`) and `minProtocolVersion`. A missing `minProtocolVersion` means the Device speaks only `protocolVersion`, as Devices before negotiation did.
- The Server answers with the newest version both speak in `ConnectResult.protocolVersion`, or a `protocol_mismatch` whose data is its `ProtocolRange`. Browser HTTP authentication applies the same rule and returns 409 with the range as its body.
- A Server that rejects without a range only takes its own version, so the Device retries with each older version it speaks before giving up. A range lets the Device say which side is too old.
- A Device reads a mismatch as `ProtocolMismatchException` and only `forbidden` as `AuthException`; any other connect failure is retried and never unpairs the Device.
- After it has connected once, or while showing a cached Server, a mismatch leaves the Device in `ConnectionState.Disconnected(incompatible = true)`: the chat shows why at once, and the client retries every minute in case either side updates.
- Unknown enum values from a newer peer don't fail decoding: `ProtocolJson` coerces a single value to its default, and handshake enum sets (`ServerFeatures`, `DeviceToolKinds`) drop unknown members.

## Consequences

- With `Minimum == Current`, there is one version; raising `Current` without raising `Minimum` lets older and newer builds keep connecting, as long as code paths for the older version remain.
- Code that differs between versions branches on `ConnectResult.protocolVersion`; tests cover both ends of the range.
- Servers and Devices from before negotiation compare versions exactly, so they still need the same `Current`.
