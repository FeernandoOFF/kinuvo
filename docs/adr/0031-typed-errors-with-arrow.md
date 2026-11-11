---
title: "Typed errors with Arrow"
description: "Use Arrow Raise and sealed errors for expected failures, with wire errors carrying a code."
---

Status: Accepted

## Context

Wire errors are strings, many call sites use broad `runCatching` or catch blocks, and IDs are plain strings. Clients cannot distinguish failure kinds.

## Decision

- Expected feature failures in `server/core` use Arrow `Raise<E>`; selected public boundaries return `Either`. Each feature has a sealed error type mapped to `WireError(code, message, data)`.
- The SDK maps unknown codes and transport errors, keeping its authentication and protocol-mismatch behaviour.
- Exceptions remain for invariant violations. Adapters translate IO, SDK and platform failures into feature errors.
- No blanket `runCatching` ban, but suspend code handles failures without swallowing cancellation.
- Retries (`Schedule`) apply only to named transient failures at the smallest safe boundary. No Saga, CircuitBreaker or `resourceScope` rewrite.
- IDs are value classes in `protocol`.
- Raise helpers use context parameters from `arrow.core.raise.context.*`.

## Consequences

Adoption is per feature, as each handler moves into the route registry. js and wasm support is checked when `protocol` and `client` adopt Arrow, and value-class IDs are checked for iOS export.

Wire errors are in place: `Frame.Response.error` is a `WireError`, with the codes `invalid_params`, `not_found`, `forbidden`, `conflict`, `unavailable`, `protocol_mismatch` and `internal`. Until a feature adopts `Raise`, `server/core` throws `ServerError`, a sealed exception per code that the route registry maps to a `WireError`; decode failures and `require` violations are `invalid_params`, any other exception is `internal`. The SDK turns an error response into a sealed `KinuvoException` carrying the code, and any code it doesn't know into its generic `Server` case. A Device wording of a refusal comes from the code, not from the Server's text.
