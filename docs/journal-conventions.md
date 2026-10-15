# Markdown Knowledge conventions

Daily journals use type `Journal`, title `YYYY-MM-DD` in the user's local date, and property `date: YYYY-MM-DD`. Search for that exact title and type before creating a note. Update the existing note when it is present.

When asked to prepare a journal, use `ask_user` for the user's reflections and read `sleep_sessions` and `workouts_list` for the relevant dates. Use `device_tools` to choose the Device when necessary. A Device read can wait for the phone to open; respect Skip and record unavailable information as unavailable. An empty Health result is not evidence of zero sleep or no exercise.

Keep Agent-generated content beneath `## Kinuvo`, between these markers:

```markdown
<!-- kinuvo-journal:start -->
### Reflections
The user's answers, in their words.

### Sleep
Sleep window, duration and available stages, with source and read time.

### Workouts
Workout type, time, duration, distance and energy when available, with source and read time.
<!-- kinuvo-journal:end -->
```

For a new journal, create the note with this section. For an existing note, read the complete section and replace only the exact marked block using `knowledge_rewrite`. If the section is absent, append it under `Kinuvo`. Preserve all writing outside the markers. If markers are duplicated or incomplete, ask the user before changing the note. Repeat updates refresh this block rather than appending another summary. Knowledge changes remain visible and undoable.

Include only information supplied by the user or returned by tools. Label invented examples as sample data and keep them separate from dated personal entries. Contacts are queried only when relevant to the user's request; the journal does not import an address book.
