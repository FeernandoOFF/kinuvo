---
title: "Typed errors with Arrow"
description: "Use Arrow Raise and sealed errors for expected failures, with wire errors carrying a code."
---

Status: Accepted

## Context

Wire errors were strings, many call sites used broad `runCatching` or catch blocks, and IDs were plain strings. Clients could not distinguish failure kinds.

## Decision

- Expected feature failures in `server/core` use Arrow `Raise<E>` through context parameters (`context(_: Raise<AgentError>)`), with helpers imported from `arrow.core.raise.context.*`. Each feature has a sealed error type (`AgentError`, `SessionError`, `RoutineError`, `ExecutionError`, `DeviceError`, ...) implementing `ServerError`, which carries the wire `code`, message and optional data.
- The route registry in `server/rpc` runs every handler inside `either` with `Raise<ServerError>` in scope and sends a raised error as a `WireError(code, message, data)`, in one place. Decode and `require` failures are `invalid_params`; any other exception is `internal`.
- Contracts that can only throw (`ExecutionDispatch`, plugin callbacks, `Screens`) carry the error in a `ServerException`, which maps to the same `WireError`; `throwing {}` converts a raised error at those edges.
- A `forbidden` that needs management access says so in `WireError.data` (`{"reason":"management"}`). Clients word only that refusal as management guidance and show the Server's message for any other.
- The SDK keeps `KinuvoException` for callers that throw and adds `KinuvoClient.request`, which returns `Either<KinuvoError, R>` with a sealed `KinuvoError` that mirrors the codes. An unknown code is its generic `Server` case; `connect` keeps `AuthException` (only for `forbidden`) and `ProtocolMismatchException`.
- Exceptions remain for invariant violations. Adapters translate IO, SDK and platform failures into feature errors.
- No blanket `runCatching` ban, but suspend code handles failures without swallowing cancellation: `Either.catch`, the app's `catching` helper, or a rethrown `CancellationException`. Cleanup in `finally` or `NonCancellable` blocks may ignore failures.
- Retries (`Schedule`) apply only to named transient failures at the smallest safe boundary. The SDK's reconnect loop takes its waits from an exponential `Schedule`, capped and jittered. Model calls already retry 429 and 5xx through Koog's `RetryingLLMClient`. No Saga, CircuitBreaker or `resourceScope` rewrite.
- IDs are value classes in `protocol` (`AgentId`, `SessionId`, `RunId`, `ToolCallId`, `InteractionId`); they encode as plain JSON strings. SwiftUI reaches them as strings through `NativeIds`.

## Consequences

`arrow-core` runs on jvm, android, iOS, js and wasmJs; `client/sdk` exports it and keeps `arrow-resilience` to itself. `server/core` exports it for the route registry and host modules.

Wire errors are `Frame.Response.error`, a `WireError`, with the codes `invalid_params`, `not_found`, `forbidden`, `conflict`, `unavailable`, `protocol_mismatch` and `internal`. Services that move into a new feature raise its sealed error; a new code needs a case in `KinuvoError`, or lands in its `Server` case.
