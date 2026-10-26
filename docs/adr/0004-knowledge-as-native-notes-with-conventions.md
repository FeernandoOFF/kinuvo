---
title: "Knowledge as native notes, with the user's conventions"
description: "Kinuvo knowledge as native notes, with the user's conventions."
---

Kinuvo first modelled Knowledge as its own ontology: Entities of Kinuvo types (`person`, `project`…) with aliases, typed links and a dated log, which each provider had to map onto its system. That broke on real data: an Anytype space keeps people as `profile` ("Human"), so `person` searches missed them; links and facts that didn't map went into a hidden `kinuvo_entity_data` property instead of the note; existing note bodies couldn't be changed at all. And it fixed one way of organizing notes for every user.

The seam (`KnowledgeSource`) now speaks each system's own terms: its schema of types and properties, and Notes with a Markdown body where links read `[[ref|label]]`. Writes are a small set of edits every note system can do natively (set properties, append under a heading, link existing text, replace text), and each source owns its undo token, so undo stays exact (a file's previous text, Anytype's inserted blocks and ETag). How a user organizes their notes is prose, their Conventions, kept per provider in `~/.kinuvo/conventions/` and given to Agents, including any journal or daily notes: Kinuvo has no built-in journal. Markdown offers starter types when `.kinuvo/types.yaml` is missing and accepts the user's own types and properties. Replacing text the user wrote is a separate tool that needs an Approval.

Accepted cost: Agents follow Conventions rather than code enforcing them, so results depend on how clearly they're written. Switching provider doesn't migrate notes. Every change-log entry records its source's scope and native undo token.
