---
title: "Memory and Skills"
description: "What an Agent remembers and how it loads reusable instructions."
---

Memory is what an Agent chooses to keep long-term in `MEMORY.md`. User Notes in `USER.md` hold what it knows about you and are editable by you. They belong to that Agent; [Knowledge](knowledge.md) is your separate, shared record in your note system.

A **Skill** is a named set of instructions and reference files for a kind of task. It is a folder containing `SKILL.md`, with frontmatter naming the Skill and describing when it applies, plus any supporting files.

**Activation** loads the full instructions into a Session. An Agent can activate a Skill, or you can select it. Descriptions let the Agent discover Skills without loading all their text into every Session.

## Skill locations

Within the Server home, Kinuvo searches:

1. `agents/<id>/skills/` for the Agent's own Skills.
2. `skills/` for shared Skills.
3. The Agent's `skillDirs` for extra, read-only Skill folders.

The Agent's own Skill takes priority over a shared Skill of the same name. External folders are read-only through Kinuvo. [Reflection](reflection.md) can draft a Skill, but it is saved only after your Approval.

```sh
kinuvo -a default skills list
kinuvo -a default skills show research
kinuvo -a default skills install /path/to/research
```

Use `--shared` on install to make it available to every Agent. A Skill adds instructions; plugins and MCP supply executable tools. See [plugins](../reference/plugins.md), [MCP](../guides/mcp.md) and the [CLI reference](../reference/cli.md).
