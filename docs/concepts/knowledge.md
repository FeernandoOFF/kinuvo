---
title: "Knowledge"
description: "Your own notes, shared across Agents and kept in your note system."
---

Knowledge is your record of your world: people, projects, places and other Notes. It stays in your note system, such as an Obsidian vault or Anytype space, and is shared by every Agent.

Kinuvo uses that system's own types, properties and Markdown bodies. Links in Note bodies use `[[ref|label]]`. It does not impose one set of types on every user.

**Conventions** explain how you organize Notes: which types hold people, where facts go, how you link them, and any journal structure you use. They are saved per provider in the Server home's `conventions/` folder. Kinuvo has no built-in journal.

During requested work an Agent can change Knowledge through its tools, subject to Approvals. Background [Reflection](reflection.md) proposes Knowledge changes for you to review first. Applied changes are listed and can be undone; each change records the source scope and its native undo information.

Replacing text you wrote uses a separate tool that needs an Approval. Switching Knowledge providers does not migrate Notes.

[Connect your notes](../guides/notes.md) to get started. The design is recorded in [ADR 0004](../adr/0004-knowledge-as-native-notes-with-conventions.md).

<div class="k-future">

**Future enhancements**

- **More Knowledge providers** beyond Markdown folders (such as Obsidian vaults) and Anytype.

</div>
