---
title: "Image generation through a tool"
description: "Generate images through one optional Server-selected image model and a tool."
---

Status: Accepted

## Context

Image generation should work with connected providers without requiring a separate model selection.

## Decision

The Server has one optional fallback image model, identified by provider and model. Unset means Automatic (best available). A usable image-capable Session model takes precedence, followed by the configured fallback, then the best available image model. Automatic ranking prefers full GPT Image models, GPT Image mini, other explicitly image-named models, other image-output models, DALL-E 3, then older DALL-E. Within a group model IDs sort descending, then provider IDs ascending, making selection deterministic. Providers own image-generation requests and credentials through the `ImageGeneration` SPI. Model catalogs report output modalities (including tool-mediated image output for documented Codex models); Settings offers only models with image output.

`generate_image` is available in Sessions and reports a clear configuration error when no usable image model exists. Reference IDs must belong to the current Session; only while the tool is offered, user turns list their image attachment IDs so the model can pass them. Generated images use the existing tool-result Attachment path, including model replay and chat rendering. OpenAI uses the Images API, Custom providers use multimodal chat completions, and ChatGPT uses the Responses image-generation tool.

Per-task model roles remain future work.

The Codex fallback list follows the [documented Responses image-tool models](https://developers.openai.com/api/docs/guides/tools-image-generation#supported-models). Explicit catalog output modalities take precedence.

## Consequences

Only usable image models are selected; per-task model roles remain future work.
