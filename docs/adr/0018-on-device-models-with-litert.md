---
title: "On-device models with LiteRT-LM"
description: "Server-owned downloads of open models, run on Android and JVM Servers with LiteRT-LM."
---

Agents can run on open models on the Server's own machine, without an account or network access once downloaded. Android and desktop come first; iOS keeps Apple's Foundation Models.

## Decision

Use [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) for inference. One Kotlin implementation (`harness/providers/litert`) runs on Android (`litertlm-android`) and JVM Servers (`litertlm-jvm`) with the same `.litertlm` files, on the GPU where available. llama.cpp was the alternative: more models and a mature iOS path, at the cost of native builds on every platform.

Models belong to the Server, like voice models. The `local` provider exposes them through `ModelProvider.downloads`; `ProviderInfo.downloads` carries the catalog, installed state and progress, and `providers.download`, `providers.cancelDownload` and `providers.removeModel` manage them. Any management Device can manage a Server's models, and clients need no special case for the embedded Server.

Downloads go through `ModelFiles` (`harness/models`), shared with Server voice: pinned URLs, sizes and SHA-256 hashes, staging, atomic moves, resumable fetches and leases that block removal while a model is loaded.

The catalog pins three models with a minimum device RAM each. The provider hides models the machine can't run and reports undownloaded ones as unavailable. Onboarding lists the provider last and requires the chosen model to be downloaded before continuing.

## Consequences

- The JVM package bundles LiteRT-LM's native libraries for macOS arm64, Linux and Windows (about 130 MB); Android adds about 22 MB per 64-bit ABI and has no 32-bit build.
- Each request replays history into a new conversation, so long Sessions pay prefill on every step. Reusing a conversation's KV cache across steps is future work.
- Small models need a small, simple tool set: tool aliases are shortened for the model, screen control, Scripts and the tool catalog are excluded, and unparseable calls are retried with a correction.
