---
title: "Testing against a real Server"
description: "Client and UI tests run on a real in-process Server instead of mocked HTTP."
---

Status: Accepted

## Context

Compose e2e tests use a mock Server over WireMock fixtures, which drift from the real protocol and cannot seed runtime state. Many tests use hand-rolled flow assertions and sleeps.

## Decision

- `TestServer` (JVM) wraps the Server runtime with a temporary home, a scripted harness, scenario seeding (transcripts, Board, Knowledge, Routines, Skills, fake Browser and Computers, external-session state) and a request spy for call counts.
- Compose e2e tests run on `TestServer` from `test@jvm`. WireMock and `MockServer` are removed entirely.
- Maestro keeps a narrow set of device flows against a real fixture Server started by a test-only launcher, not shipped in the `kinuvo` CLI. Mockstro adaptation is deferred, so `mockstrow` regression runs pause until then.
- Turbine asserts flows. `runTest` with an injected clock is used where virtual time is intended; sleeps are removed.
- TestBalloon is introduced first in JVM-only test sources. `shared/ui` and tests compiled for iOS stay on kotlin-test until TestBalloon failures fail runs on every target.
- Tests are audited by covered behaviour: keep public-interface tests, rewrite sleepy ones, delete private-detail and duplicate tests, tag credentialed tests `Live`.

## Consequences

UI and client tests exercise the real protocol. Device-level checks run outside Mockstro until it is adapted; `AGENTS.md` and `.maestro/README.md` say so.
