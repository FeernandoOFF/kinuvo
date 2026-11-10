---
title: "On-device models with LiteRT-LM"
description: "Download verified Server-owned models and run them with LiteRT-LM."
---

Status: Accepted

## Context

Owners need verified local models managed on the Server rather than each Device.

## Decision

Agents can run on open models on the Server's own machine, without an account or network access once downloaded. Android and desktop come first; iOS keeps Apple's Foundation Models.

Use [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) for inference. One Kotlin implementation (`harness/providers/litert`) runs on Android (`litertlm-android`) and JVM Servers (`litertlm-jvm`) with the same `.litertlm` files, on the GPU where available. llama.cpp was the alternative: more models and a mature iOS path, at the cost of native builds on every platform.

Models belong to the Server, like voice models. The `local` provider exposes them through `ProviderAdmin.downloads`; `ProviderInfo.downloads` carries the catalog, installed state and progress, and `providers.download`, `providers.cancelDownload` and `providers.removeModel` manage them. Any management Device can manage a Server's models, and clients need no special case for the embedded Server.

Downloads go through `ModelFiles` (`harness/models`), shared with Server voice: pinned URLs, sizes and SHA-256 hashes, staging, atomic moves, resumable fetches and leases that block removal while a model is loaded.

The catalog pins three models with a minimum device RAM each. The provider hides models the machine can't run and reports undownloaded ones as unavailable. Onboarding lists the provider last and requires the chosen model to be downloaded before continuing.

## Consequences

- Desktop and bundled-runtime CLI packages keep only the LiteRT-LM Kotlin classes. The first model download also downloads the pinned 0.19.0 jar from Google Maven, verifies its SHA-256, and extracts only this machine's engine into `<Server home>/engines/litertlm-0.19.0-<platform>`. Engine bytes appear in the existing model download progress. A pre-existing model also needs this engine before it is available. Android still bundles its native engine and adds about 22 MB per 64-bit ABI; iOS is unchanged.
- The universal Server jar retains its multi-platform libraries for portability, but the JVM provider uses the verified engine store too. Engines are shared by models and retained when a model is removed. First setup requires internet access; cached models and engines work offline.
- Each request replays history into a new conversation, so long Sessions pay prefill on every step. Reusing a conversation's KV cache across steps is future work.
- Small models need a small, simple tool set: screen control, Scripts and the tool catalog are excluded, and unparseable calls are retried with a correction.
- Small models can't repeat hashed tool aliases, so every run now offers tools by their own names and keeps aliases only for clashes (see [Plugins](../reference/plugins.md)).
