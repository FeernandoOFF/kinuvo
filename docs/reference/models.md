---
title: "Models and provider plugins"
description: "Kinuvo models and provider plugins."
---

Choose an Agent's provider and model in Settings → Agent → Model. The composer can choose a different provider/model for one Session; “Agent's model” clears that choice and its reasoning setting. Changes take effect on the next run. Both places support custom model IDs and a manual refresh.

## Discovery and metadata

The Server exposes the authenticated `models.list` method (`ModelsParams(refresh = false)`). Installed `ModelProvider` plugins discover the runnable models. The open-source [Models.dev catalog](https://github.com/anomalyco/models.dev), fetched from `https://models.dev/api.json`, fills missing display names, reasoning options, modalities, tool support, context limits and reference API costs. Provider data takes precedence. Models.dev cannot make an uninstalled provider or undiscovered model appear as runnable.

Codex discovers models through the existing ChatGPT login and authenticated Codex models endpoint, using compatibility version `0.160.1`. Hidden models are excluded. This version describes the endpoint contract; model IDs are never hard-coded in the picker. The backend is undocumented and may require future compatibility updates.

Reference costs, when present in the protocol, are USD per million tokens from Models.dev. They describe API pricing, not ChatGPT subscription billing; the picker does not display them. The resolved metadata configures both Session capabilities and the Koog `LLModel` used for execution; explicit unsupported tools, inputs and reasoning are not advertised to the client. Unknown fields remain unknown in the catalog. Metadata does not install plugins.

## OpenAI API and ChatGPT subscription

The Server offers `codex` (ChatGPT subscription sign-in) and `openai` (OpenAI API key). They share the Koog Responses adapter, with separate authentication and model discovery.

For API access, open **Settings → Model providers → OpenAI API** to paste a key or use **From Proton Pass** (when connected) to choose a Secret reference. The client sends the setting to the selected Server and never reads a saved key back. A saved setting takes precedence over `OPENAI_API_KEY`; removing it returns to the environment value, if any. Refresh models after changing credentials.

Pasted keys use the Server’s OS keychain (`kinuvo` service, `openai` account). Without an OS keychain, the key is stored as plaintext in `<Kinuvo home>/credentials/openai-key`, readable and writable only by its owner (0600). Secret references are stored in `<Kinuvo home>/credentials/openai-reference`; their resolved values stay in the existing five-minute Secrets memory cache and are never saved. These locations belong to the Server, including when it runs embedded on a device.

Alternatively, set `OPENAI_API_KEY` in the Server's process environment or `~/.kinuvo/.env`. A password-manager Secret reference can be used as the value, for example:

```dotenv
OPENAI_API_KEY=${pass://Kinuvo/OpenAI/api key}
```

Choose **OpenAI API** in the Agent or Session model picker and refresh its models. Keys stay on the Server. API models are discovered from `https://api.openai.com/v1/models`; the picker excludes known audio, image-generation, embedding and other non-chat model families. Custom IDs remain available. API usage is separate from the ChatGPT subscription. See the [OpenAI API quickstart](https://developers.openai.com/api/docs/quickstart).

Tool definitions have stable ordering and recursively sorted schema object keys. Conventions remain in late Session Context, so changing them does not rewrite tool descriptions. The chat runner allows 30 model steps and reports exhausted work as an error rather than normal completion.

Compaction can summarize completed tool exchanges within the current turn while keeping its user request, current Context and activated Skills. The full transcript remains on disk. Input estimates include tool definitions and reserve output space for known context windows; oversized summaries are chunked, and requests that still cannot fit are rejected before inference.

## Apple on-device models

On macOS, the desktop and standalone Servers automatically offer **Apple → Apple on-device model** when Apple's `/usr/bin/fm` is installed. Enable Apple Intelligence and review and accept the CLI license on the Mac running the Server with `sudo fm license`, then refresh models. Kinuvo shows setup instructions while the license or model is unavailable. No API key is needed. See the [Mac setup guide](../guides/apple-models.md) for requirements, troubleshooting and limits.

On iOS, the embedded Server uses Apple's native Foundation Models framework; the CLI license step applies only to Mac Servers.

## OpenAI-compatible endpoints

JVM and embedded mobile Servers can connect to OpenAI-compatible Chat Completions APIs through Koog. Add named endpoints to `providers` in the Server home's `config.yaml`, then restart the Server:

```yaml
providers:
  local-models:
    name: Local models
    baseUrl: http://localhost:1234/v1/
    apiKeyRequired: false
  hosted-models:
    name: Hosted models
    baseUrl: https://models.example.com/v1/
    apiKeyEnv: HOSTED_MODELS_API_KEY
```

Each entry's key becomes a provider ID in the Agent and Session model pickers. Include the API version prefix in `baseUrl`: the client appends `models` and `chat/completions`. Addresses are relative to the Server. Local runtimes such as Ollama can be configured through their OpenAI-compatible API.

Authenticated endpoints default to `OPENAI_COMPATIBLE_API_KEY` when `apiKeyEnv` is omitted. Configure their keys or Secret references in **Settings → Model providers**, or set the named Server environment variable. Each endpoint has separate credential storage using its provider ID. Set `apiKeyRequired: false` for unauthenticated endpoints; a configured environment key is still sent when present.

An optional `models` list replaces discovery and describes known capabilities:

```yaml
providers:
  local-models:
    baseUrl: http://localhost:1234/v1/
    apiKeyRequired: false
    models:
      - id: my-model
        tools: true
        input: [text, image]
        reasoning: []
        context: 32768
```

Model IDs are sent unchanged. Unknown capabilities remain unspecified. Text and image attachments use the existing shared conversion; native document attachments and provider-specific reasoning extensions are not supported by this adapter. OpenAI encrypted reasoning from an existing Session is omitted when switching to a compatible endpoint. Codex, OpenAI Responses, Apple and LiteRT on-device inference retain their existing behavior.

## On-device models

The `local` provider (“On this device”) runs open models on the Server's own machine with [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM), so conversations never leave it. It is available on Android embedded Servers and on JVM Servers (desktop and `kinuvo serve`) running on Apple silicon macOS, 64-bit Linux or 64-bit Windows. iOS keeps Apple's on-device models.

Download a model in **Settings → Model providers → On this device**, or pick it during onboarding. Any management Device can start, cancel or remove a download on the selected Server, e.g. a phone downloading onto a desktop Server. Progress follows `providers.changed`.

| Model | Download | Device RAM | Context | Tools |
| --- | --- | --- | --- | --- |
| Gemma 4 E2B | 2.6 GB | 6 GB | 8,192 | 8 |
| Gemma 4 E4B | 3.7 GB | 10 GB | 8,192 | 12 |
| Qwen3 0.6B | 614 MB | 3 GB | 4,096 | 4 |

All three are Apache-2.0 `.litertlm` files from `litert-community` on Hugging Face, pinned by revision, size and SHA-256. Models the machine can't run are listed with the reason and aren't offered in pickers. Files live in `<Server home>/local-models/<id>/`; an interrupted download resumes, and canceling discards it.

One model is loaded at a time and unloaded after five idle minutes. Loading tries the GPU (Metal, OpenCL) and falls back to the CPU. Each request replays the Session history into a new LiteRT-LM conversation; Kinuvo's agent loop runs the tools. Tool aliases are shortened to their local names for the model, and screen control, Scripts and the tool catalog are left out. A tool call LiteRT-LM can't parse is retried twice with a correction. Replies are capped at 1,024 tokens. Reasoning is off and input is text only.

Qwen3 0.6B is the smallest download but only handles short, simple turns. Gemma 4 E2B is the recommended default.

## Cache and offline use

The Server saves successful discovery and metadata in `~/.kinuvo/cache/models.json` with owner-only permissions. Provider discovery refreshes after an hour; Models.dev metadata refreshes after 24 hours. Each fetch has a ten-second timeout. Failed refreshes keep the last successful result, show a warning and throttle automatic retries for a minute. “Refresh models” bypasses the cache and retry throttle.

Existing Agent configuration and Session transcripts remain valid. A model absent from discovery can still be entered by ID; the selected provider validates access when running it. Existing Session settings without a provider inherit the Agent's provider. New choices save the provider and model together, so identical model IDs in different plugins route correctly.

The discovery cache belongs to the Server and is keyed by provider, not account. After changing the logged-in account, use “Refresh models” to replace the saved list. Offline lists indicate prior availability and cannot guarantee current account access.

## Adding a provider

Implement `ModelProvider` and expose it through a `HarnessPlugin.modelProviders` list:

```kotlin
override val id = "my-provider" // agent.yaml provider key
override val name = "My provider"
override val catalogId = "models-dev-provider-key" // optional
override suspend fun models(): List<ModelInfo> = discoverFromProvider()
override fun model(config: AgentConfig): Model = buildModel(config)
override fun model(config: AgentConfig, info: ModelInfo?): Model = buildModel(config, info)
```

Add the plugin to `jvmPlugins` in `harness/server/jvm/src/Plugins.kt`, or pass it in `Harness(plugins = ...)` for another host. Plugins are compiled into the host today. Only installed plugins are exposed. Discovery runs on the Server; credentials never go to Models.dev or clients. Plugins without discovery keep working through custom IDs. Use `catalogId = null` for providers without a matching Models.dev catalog.

`ModelInfo.reasoning = null` means unknown; an empty list means unsupported; otherwise the list contains the accepted values. `defaultReasoning` is optional. The UI uses those options when selecting a model and preserves custom IDs. Codex omits the reasoning parameter when the selected effort is `none`.
