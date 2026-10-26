---
title: "Connect your notes"
description: "Use an Obsidian vault, Markdown folder or Anytype space as Knowledge."
---

Set up Knowledge in **Settings → Knowledge** on a management Device. Every Agent on this Server uses the selected source.

## Obsidian and Markdown

Choose the Markdown provider and a folder the Server can access. This can be an Obsidian vault or another Markdown notes folder. With no path set, it uses `<Server home>/knowledge`.

```yaml
knowledge:
  provider: markdown
  path: /home/me/notes
```

Paths belong to the Server machine, even when you configure them from a phone. The provider uses native Markdown files and frontmatter. Its optional `.kinuvo/types.yaml` describes your own Note types and properties; starter types are available when it is absent.

Write Conventions in Settings explaining where people, projects and other Notes belong, which properties you use and how to link them. These instructions are stored in `conventions/markdown.md` under the Server home. Kinuvo has no built-in daily-note or journal structure; describe your existing one.

## Anytype

Choose Anytype on a Server that offers its plugin. Supply an API URL reachable from the Server, an API key and a space ID. A local API defaults to `http://127.0.0.1:31009`; on a remote Server, loopback refers to that Server, not the Device running the settings screen.

```yaml
knowledge:
  provider: anytype
  options:
    url: http://127.0.0.1:31009
    apiKey: ${pass://Kinuvo/Anytype/key}
    space: YOUR_SPACE_ID
```

Use the space's existing types and properties in your Conventions, saved as `conventions/anytype.md`. For example, tell the Agent the actual type you use for people rather than assuming it is named `person`. [Secret references](../reference/secrets.md) keep the key in your password manager.

## Check changes

Ask an Agent to find a known Note before asking it to edit. Review applied changes and undo them in the app or CLI:

```sh
kinuvo knowledge search "Trip"
kinuvo knowledge changes
kinuvo knowledge undo CHANGE_ID
```

Requested work can apply changes through tools under the usual Approvals. Reflection proposes changes for review first. Switching providers does not migrate Notes. See [Knowledge](../concepts/knowledge.md), [Reflection](../concepts/reflection.md) and [ADR 0004](../adr/0004-knowledge-as-native-notes-with-conventions.md).

<div class="k-future">

**Future enhancements**

- **More Knowledge providers** beyond Markdown folders (such as Obsidian vaults) and Anytype.

</div>
