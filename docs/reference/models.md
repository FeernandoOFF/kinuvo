---
title: "Models and provider plugins"
description: "Choose models and configure cloud, Custom and downloaded providers."
---

Choose an Agent's provider and model in Settings → Agent → Model. The composer can choose a different provider/model for one Session; “Agent's model” clears that choice and its reasoning setting. Changes take effect on the next run. Both places support custom model IDs and a manual refresh.

## Available models

The picker lists models available from providers installed on the selected Server. Refresh after signing in or changing credentials. You can also enter a custom model ID.

Provider information and the [Models.dev catalog](https://models.dev) supply capability details such as supported inputs, tools, reasoning options and context size. Missing information stays unknown; a catalog entry alone does not make a model available on your Server.

## OpenAI API and ChatGPT subscription

The Server offers `codex` (ChatGPT subscription sign-in) and `openai` (OpenAI API key). Sign in to each separately.

For API access, open **Settings → Model providers → OpenAI API** to paste a key or use **From Proton Pass** (when connected) to choose a Secret reference. The client sends the setting to the selected Server and never reads a saved key back. A saved setting takes precedence over `OPENAI_API_KEY`; removing it returns to the environment value, if any. Refresh models after changing credentials.

Pasted keys use the Server’s OS keychain (`kinuvo` service, `openai` account). Without an OS keychain, the key is stored as plaintext in `<Kinuvo home>/credentials/openai-key`, readable and writable only by its owner (0600). Secret references are stored in `<Kinuvo home>/credentials/openai-reference`; their resolved values stay in the existing five-minute Secrets memory cache and are never saved. These locations belong to the Server, including when it runs embedded on a device.

Alternatively, set `OPENAI_API_KEY` in the Server's process environment or `~/.kinuvo/.env`. A password-manager Secret reference can be used as the value, for example:

```dotenv
OPENAI_API_KEY=${pass://Kinuvo/OpenAI/api key}
```

Choose **OpenAI API** in the Agent or Session model picker and refresh its models. Keys stay on the Server. API models are discovered from `https://api.openai.com/v1/models`; the picker excludes known audio, image-generation, embedding and other non-chat model families. Custom IDs remain available. API usage is separate from the ChatGPT subscription. See the [OpenAI API quickstart](https://developers.openai.com/api/docs/quickstart).

## Model tiers

Each model has a tier, shown beside its name in the model picker. The tier sets how much of Kinuvo's instructions and tools the model gets, and how many steps it may take in one turn.

| Tier | Prompt | Tools | Steps |
| --- | --- | --- | --- |
| No tools | Basic | None | 1 |
| Micro | Basic | Memory and web search (no web fetch), at most 5, simple parameters; latest message plus a note of the four questions before it, without earlier replies | 4 |
| Small | Basic with a short Identity | Memory, web, Knowledge, Tasks and Routines, at most 8, simple parameters | 10 |
| Mid | Full | All except screen, browser, Script and Device tools; MCP tools through the tool catalog | 50 |
| Frontier | Full | All | 66 |

A step is one model request, which can include tool calls. The Server picks the tier: the Agent's `tier`, then a built-in provider's own choice, then an inference from the model (no tool support, a 4,096-token context or less, on-device or up to 8B parameters, up to 70B or a small-model name, otherwise Frontier). Set `tier` on a model in a Custom provider's `models` list to override the inference for it, or on an Agent in agent.yaml for all its models.

## Apple on-device models

On macOS, the desktop and standalone Servers automatically offer **Apple → Apple on-device model** when Apple's `/usr/bin/fm` is installed. Enable Apple Intelligence and review and accept the CLI license on the Mac running the Server with `sudo fm license`, then refresh models. Kinuvo shows setup instructions while the license or model is unavailable. No API key is needed. See the [Mac setup guide](../guides/apple-models.md) for requirements, troubleshooting and limits.

On iOS, the embedded Server uses Apple's native Foundation Models framework; the CLI license step applies only to Mac Servers.

## OpenAI-compatible endpoints

JVM and embedded mobile Servers can connect to OpenAI-compatible Chat Completions APIs through their Chat Completions API. Add named endpoints to `providers` in the Server home's `config.yaml`, then restart the Server:

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

Each entry's key becomes the provider ID `custom/<key>` in the Agent and Session model pickers, so it never replaces a built-in provider such as `openai` or `codex`. An agent.yaml `provider:` may still name a custom provider by its bare key unless a built-in provider (`openai`, `codex`, `apple`, `local`) has that ID; then the bare key means the built-in one. On upgrade, Agents and Sessions that named a custom provider sharing a built-in ID are pointed at `custom/<key>` once. Include the API version prefix in `baseUrl`: the client appends `models` and `chat/completions`. Addresses are relative to the Server. Local runtimes such as Ollama can be configured through their OpenAI-compatible API.

Authenticated endpoints default to `OPENAI_COMPATIBLE_API_KEY` when `apiKeyEnv` is omitted. Configure their keys or Secret references in **Settings → Model providers**, or set the named Server environment variable. Each endpoint has separate credential storage (`custom-<key>`). A key saved earlier under the bare key moves there on first use; a bare `openai` key stays with the built-in OpenAI API provider and is only copied. Set `apiKeyRequired: false` for unauthenticated endpoints; a configured environment key is still sent when present.

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
        tier: Mid
```

Model IDs are sent unchanged. Unknown capabilities remain unspecified. Text and image attachments are supported; native document attachments and provider-specific reasoning extensions are not supported by Custom providers. OpenAI encrypted reasoning from an existing Session is omitted when switching to a compatible endpoint. Codex, OpenAI Responses, Apple and LiteRT on-device inference retain their existing behavior.

## On-device models

The `local` provider (“On this device”) runs open models on the Server's own machine with [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM), so model inference stays on that machine. Tools may still contact other services. It is available on Android embedded Servers and on JVM Servers (desktop and `kinuvo serve`) running on Apple silicon macOS, 64-bit Linux or 64-bit Windows. iOS keeps Apple's on-device models.

Download a model in **Settings → Model providers → On this device**, or pick it during onboarding. Any management Device can start, cancel or remove a download on the selected Server, e.g. a phone downloading onto a desktop Server. You can follow progress in Settings.

| Model | Download | Device RAM | Context | Tools |
| --- | --- | --- | --- | --- |
| Gemma 4 E2B | 2.6 GB | 6 GB | 8,192 | 8 |
| Gemma 4 E4B | 3.7 GB | 10 GB | 8,192 | 10 |
| Qwen3 0.6B | 614 MB | 3 GB | 4,096 | 5 |

All three are Apache-2.0 `.litertlm` files from `litert-community` on Hugging Face, pinned by revision, size and SHA-256. Models the machine can't run are listed with the reason and aren't offered in pickers. Files live in `<Server home>/local-models/<id>/`; an interrupted download resumes, and canceling discards it.

One model is loaded at a time and unloaded after five idle minutes. Loading tries the GPU (Metal, OpenCL) and falls back to the CPU. Each request replays the Session history into a new LiteRT-LM conversation; Kinuvo's agent loop runs the tools. Qwen3 0.6B is Micro and the Gemma models are Small, so they get the short basic prompt and only [their tier's tools](#model-tiers). Tool caps: 5 (Qwen3 0.6B), 8 (Gemma 4 E2B), 10 (Gemma 4 E4B). A tool call LiteRT-LM can't parse is retried twice with a correction. Replies are capped at 1,024 tokens. Reasoning is off and input is text only.

Qwen3 0.6B is the smallest download but only handles short, simple turns. Gemma 4 E2B is the recommended default.

## Cache and offline use

The Server saves successful discovery and metadata in `~/.kinuvo/cache/models.json` with owner-only permissions. Provider discovery refreshes after an hour; Models.dev metadata refreshes after 24 hours. Each fetch has a ten-second timeout. Failed refreshes keep the last successful result, show a warning and throttle automatic retries for a minute. “Refresh models” bypasses the cache and retry throttle.

Existing Agent configuration and Session transcripts remain valid. A model absent from discovery can still be entered by ID; the selected provider validates access when running it. Existing Session settings without a provider inherit the Agent's provider. New choices save the provider and model together, so identical model IDs in different plugins route correctly.

The discovery cache belongs to the Server and is keyed by provider, not account. After changing the logged-in account, use “Refresh models” to replace the saved list. Offline lists indicate prior availability and cannot guarantee current account access.

## Result limits

Tool results fit the selected model's context. Micro and Small models receive up to 15% of context per result; Mid and Frontier receive 10%, capped at 8,000 and 25,000 tokens. Micro has a 400-token minimum. Earlier turns get a quarter of that budget. Unknown context uses 24,576 tokens as its starting budget.

Micro and Small request as many items as fit their result budget (Micro 3–6, Small 5–10) and Mid up to 20, from tools with count parameters. Frontier keeps each tool's existing defaults. Large results show their beginning and end with an omission marker in model input; the Session and app retain the full tool output.

Micro and Small can run two and four tool calls per turn respectively, then answer from their results. Their web search results use short snippets and URLs without dates. All tiers get one final chance to answer without tools when the step limit is reached; if that request fails, the run reports the unfinished-work error.
