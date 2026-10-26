---
title: "Reflection"
description: "Review how Agents curate Memory and propose Knowledge changes and Skills."
---

Reflection is an Agent reviewing a finished conversation to curate Memory and User Notes, propose durable facts for Knowledge, and draft reusable Skills.

Memory and User Notes can be curated directly. Knowledge changes and Skill drafts become **Reflection proposals**: proposed content with a reason and the conversation evidence behind it. They take effect only after your Approval. Review a proposal, approve it or reject it; a draft is not an applied change.

Knowledge's `reflection` setting controls automatic Reflection. The Server's `reflection.provider` and `reflection.model` can select the model used for Reflection; when no usable Reflection model is selected, it follows the effective Session model. `knowledge.reflectionPrompt` can supply a custom prompt.

Keep Knowledge Conventions clear so proposals fit your note system. Applied Knowledge changes remain visible in the change log and can be undone.

See [Knowledge](knowledge.md), [Memory and Skills](memory-and-skills.md), and the [Server configuration](../reference/config.md). Implementation: [Reflection](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/core/src/knowledge/Reflection.kt).
