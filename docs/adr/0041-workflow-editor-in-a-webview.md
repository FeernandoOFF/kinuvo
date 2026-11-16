---
title: "Workflow editor in a webview"
description: "The Workflow graph is edited in a bundled vanilla page on vendored Drawflow, a view over the protocol JSON that the ViewModel owns."
---

Status: Accepted

A Workflow's graph is edited in a web page inside the app's existing webview (`HtmlWebView`), not in Compose. The page is hand-written HTML, CSS and JavaScript on Drawflow 0.0.60 (MIT), with no build step, bundler, package manager or framework. Drawflow's minified script, stylesheet and licence are vendored unmodified in `client/ui/composeResources/files/workflow/`; Kotlin inlines them with the page into one document, so the editor works offline and loads nothing remote. The document runs in the same sandboxed frame as HTML Surfaces: scripts only, no network.

The page is a view. Its model is the protocol's `Workflow` JSON and Drawflow only draws it: nodes, connections and positions are never persisted or exchanged in Drawflow's own format. Steps are edited inline in their cards: an Agent turn (Agent, prompt, Session), a Model request (prompt, system prompt, model), a Tool call (name with suggestions, JSON arguments) and an Approval (question). Each card shows its step id, validated against `[a-zA-Z0-9_-]+` and for uniqueness; renaming updates the edges. The page refuses self-edges and duplicate edges; the Server stays the authority on cycles. Steps without a `position` are laid out by longest-path depth and index within the column. When given a `WorkflowRun`, each card shows its step status and a collapsed output or error.

The `WorkflowViewModel` stays the single source of truth, and the page never calls the Server. The messages are small JSON types in `client/app` (`WorkflowEditorMessages`). Kotlin to page: `load` (Workflow, catalog of Agents, models and tools, theme, run), `catalog`, `run` and `theme`. Page to Kotlin: `ready`, `changed` (the whole edited Workflow) and `open_session`. The ViewModel takes only `steps` and `edges` from `changed`, so fields edited natively are never overwritten. The page reloads its model only on `load`, which the screen sends once ready and when the draft's graph changes from outside the page (a discard or a Server change), never for a change the page made. Tool suggestions are the tools the Workflow's Agent can call, read from its Session capabilities; without an Agent the field is free text. Colours come from the app theme in light and dark.

Everything that is not the graph stays native: the Workflow list, name, trigger and schedule, enable switch, the Agent it runs as, Run and Cancel, the waiting-Approval bar, save and discard, and the run history, whose selected run is the one shown on the graph.
