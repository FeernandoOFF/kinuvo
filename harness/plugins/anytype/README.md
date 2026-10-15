# Anytype Knowledge source

Keeps Knowledge in an Anytype space through the local API v2 (space-scoped keys can't use v1). Options in `knowledge.yaml`: `url` (default `http://127.0.0.1:31009`), `apiKey` and `space`.

- **Schema**: the space's own types and properties, minus Anytype-managed ones (dates, creator, layout, backlinks, archive flags).
- **Notes**: refs are object ids. Properties read as strings: ISO dates, `true`/`false`, a select's option name, JSON arrays for multi-select, objects and files. Bodies are the blocks as Markdown; mentions and object links read `[[id|label]]`.
- **Files**: images, PDFs and other files in a body read `[[id|name]] (image)`, `(pdf)` or `(file)`. The knowledge tool's `file` action downloads one through `files/{id}/content` and shows it to the model as an attachment (supported attachment types, up to 10 MB).
- **Schema** also lists each type's templates by name.
- **Writes**: new objects start from the named template, or without one. Properties use `set_properties`; Append inserts blocks at the end or at the end of a heading's section (adding the heading when missing); Link and Replace rewrite the one block holding the text, and `[[id|label]]` becomes a mention. Replace refuses text that spans blocks or occurs more than once.
- **Undo**: archives created objects, or deletes inserted blocks and restores block text and property values. It refuses when the object changed since, unless it's back to how that write left it (so changes undo newest first).

Block edits and rollback use `If-Match` and full block IDs. Archiving a created object checks its current state first; the API's DELETE route has no conditional-write parameter, so it cannot protect against an edit between that check and archiving.

Tests use a mock API by default:

```sh
./kotlin test -m anytype -p jvm
```

Live tests use `~/.kinuvo/knowledge.yaml` with Anytype running. Read-only:

```sh
KINUVO_LIVE_ANYTYPE=1 ./kotlin test -m anytype -p jvm
```

Writes, on one uniquely named temporary page that's archived (moved to Bin) in `finally`:

```sh
KINUVO_LIVE_ANYTYPE_WRITES=1 ./kotlin test -m anytype -p jvm
```

Never print the full configuration or an Authorization header.
