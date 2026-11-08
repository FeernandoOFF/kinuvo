---
title: "Model tiers and tool scope"
description: "One model tier sets a model's prompt, tools, history and step limit; a tool scope narrows the tools of one run."
---

Status: Accepted

## Context

What a model can handle was spread over provider hooks (`basicPrompt`, `usableTools`), a shared skip list, per-provider caps and constants in the runtime. Adding a model meant touching several places, and a run could not offer fewer tools than the model's limit.

## Decision

Every model has a `ModelTier`: `NoTools`, `Micro`, `Small`, `Mid` or `Frontier`. `ModelInfo.tier` carries it to clients. A tier maps to a `ModelProfile`: prompt style (basic, basic with a short Identity, or full), tool policy (allowed `ToolGroup`s, cap, preferred order, schema level, parallel calls, catalog for External tools), history policy (latest turn only, Compaction ratio), step limit and Memory budget share. `HarnessRuntime` resolves the profile once per run; it drives the prompt, tools, Compaction, Memory caps and the step limit for chats and Routines.

The tier is the first of: the Agent's `tier:` in agent.yaml, the model's `tier:` under a Custom provider's `models` in config.yaml, the tier a built-in provider declares (Apple system model and Qwen3 0.6B are Micro; Gemma 4 E2B and E4B are Small), or an inference from the model's capabilities: no tool support is NoTools; a context of 4,096 tokens or less is Micro; on-device models and those of 8B parameters or fewer (read from the ID, E2B/E4B count as small) are Small; up to 70B parameters or a mini, flash, nano, haiku, small or lite name is Mid; everything else, including cloud models of unknown size, is Frontier.

A provider may adjust a model's profile (Apple's Private Cloud Compute and Gemma 4 E4B raise the Small tool cap) and filter tools it cannot take (Apple's schema). Tools declare a `ToolGroup`; tools without one are External, which covers MCP and other plugins.

`ToolScope` narrows the tools of one run by name or group before the tier policy, the provider filter and the cap apply, in that order. A tool the scope names skips the tier's group filter, so a larger model can hand a small one a tool its tier would not offer by default; the schema level, provider filter and cap still apply. A selection no larger than the cap is kept whole. `HarnessRuntime.runner` accepts a scope, so a future `spawn_agent` tool can start a run on a smaller model with only the tools it names.

`maxSteps` counts model requests. Each request is about three Koog graph steps, so a limit is `3 × maxSteps + 2` graph steps; Frontier's 66 requests keep the earlier 200.

## Consequences

Adding a model or provider means declaring a tier, not new hooks. Mid models send External tools through the tool catalog and leave out Automation tools unless an Agent or scope asks for them. Per-request tool selection and an eval harness remain future work.
