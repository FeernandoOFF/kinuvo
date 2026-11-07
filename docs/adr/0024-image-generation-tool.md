---
title: "Image generation through a tool"
description: "Generate images through one optional Server-selected image model and a tool."
---

Status: Accepted

## Context

Image generation needs a model selection independent of the Session’s chat model.

## Decision

The Server has one optional image model, identified by provider and model. Providers own image-generation requests and credentials through the `ImageGeneration` SPI. Model catalogs report output modalities (including tool-mediated image output for documented Codex models); Settings offers only models with image output.

`generate_image` is available when that model's provider is usable. Reference IDs must belong to the current Session; only while the tool is offered, user turns list their image attachment IDs so the model can pass them. Generated images use the existing tool-result Attachment path, including model replay and chat rendering. OpenAI uses the Images API, Custom providers use multimodal chat completions, and ChatGPT uses the Responses image-generation tool.

Per-task model roles remain future work.

The Codex fallback list follows the [documented Responses image-tool models](https://developers.openai.com/api/docs/guides/tools-image-generation#supported-models). Explicit catalog output modalities take precedence.

## Consequences

Only usable image providers expose the tool; per-task model roles remain future work.
