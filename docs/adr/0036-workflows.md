---
title: "Workflows"
description: "Durable step graphs the Server runs: Agent turns, tool calls, model requests, conditions and user checks, edited in a web view or saved by Agents."
---

Status: Accepted

## Graph

Workflows are Server-owned DAGs with multiple roots. A step waits for all its predecessors to settle without failure and runs if at least one incoming edge is live (roots always run); independent branches run in parallel. Steps with no live incoming edge are Skipped, and skipping propagates without failing the run. IDs are unique, edges must exist, cycles are rejected, and references must name ancestors. Optional positions persist the editor layout. Each run snapshots its graph so edits cannot alter work already underway. A Workflow has at most one active run. A failed step cancels sibling work and skips the rest; cancellation stops running Sessions.

## Steps

A step is an Agent turn, an Approval, a Review, a Tool call, a Model request, a Condition or a Text step.

- **Agent turn** starts an ordinary Session through the run executor, keeping its tool policy and pending interactions. `Continue` keeps the Workflow Session’s context; `New` inserts a Context break and starts fresh model history in the same Session. Optional `model: {provider, model}` sets its Session settings before the turn; otherwise the Session follows its own settings or the Agent default. Runs record the effective provider and model.
- **Tool** makes one call to any tool the Agent could call, with templated JSON arguments and no model. It passes the same gate as a run: tool scope, Deny and Trust rules, Approvals, redaction and limits. An unknown or refused tool fails the step with the refusal.
- **Model** makes one model request with no tools, using the Agent's provider and model unless the step names another. Runs record the effective provider and model. Its only context is the step's `system` text; its transcript is discarded and never listed.
- **Condition** compares templated text. Equals and NotEquals trim and compare numbers numerically when both parse; Contains and NotContains test substrings; Empty and NotEmpty treat blank text, null, [], {} and false as empty; GreaterThan and LessThan need numbers. Its output is true or false, and only its edges carry a `branch`; unlabelled edges follow any Done source.
- **Text** outputs its own text with references filled in, a starting value for later steps.
- **Approval** publishes the same pending interaction as tools, answered through `session.respond`. It cannot create Trust rules. Denial fails the step and run.
- **Review** shows a templated value as a form and continues with what the user submits. A JSON object with at most 30 keys gets one prefilled field per key (text, long text, number, yes/no, or pretty JSON for nested values); anything else is one long-text field. Answers come back with the input's keys and types, or as plain text for the single field. Skipping fails the step with "Review declined".

Tool and Model steps run as the Workflow's `agentId`, required when either is present. Neither creates a Session unless a Tool call needs an Approval or shows a Surface (`show_ui`, `show_html`, scoped to that tool alone).

## References and inputs

Outputs are strings. `{{steps.<id>.output}}` inserts one; `{{steps.<id>.output.<key or index>...}}` parses it as JSON (one Markdown fence allowed) and resolves the path, failing the step naming the reference when it can't. `{{input[.path]}}` reads the run's input: declared defaults (text, or the JSON value it parses as) overlaid with supplied JSON. `{{now}}` is the step's start time as ISO with offset in the Workflow's time zone; `{{now.date|time|weekday|timezone|utc|timestamp}}` gives one part. Missing inputs and skipped outputs render empty (null for a whole-value argument). An `args` string that is exactly one reference becomes that JSON value. A Done run exposes its Done sink's output, or an object keyed by step id for several sinks.

## Sessions

A Workflow keeps one Session per Agent, shared by Agent turns and user interactions, kept across its runs, titled with its name and found again by its id (a new one if the user deleted it). Approvals, Reviews, tools' Approvals and Surfaces all land there. Runs write Notices with the code `workflow` ("Run started", "Review “…”: submitted", "Run done"), drawn as dividers and never sent to the model. A tool call that needed an Approval is recorded there with its result. Sessions carry a Workflow reference and follow its name, including renames. They appear in Chats with a Workflow icon; a Workflow makes its Session only when a step needs it.

Agent turns sharing a Session serialize through a mutex. Step model overrides are restored after the turn, including failure and cancellation. A Context break preserves the visible transcript but discards earlier model history, Compaction and Skill activations; empty contexts get no extra break. ACP and remote harnesses retain their external session mapping: their execution API has no context-reset operation, so `New` currently resets model context only in the built-in harness. Restart recovery remains unchanged.

## Durability and retry

The Store atomically saves graphs, schedule cursors, run snapshots and step outputs. On start the Server settles each Running run: Pending stays Pending; a step waiting on its own Approval returns to Pending in its Session with a fresh interaction (pending interactions live only in memory); a Running Model step returns to Pending. A Running Tool step or Agent turn, or a tool waiting on an Approval for a call it made itself (`started`, as in `run_code`), may have had side effects, so it fails with "Interrupted by a Server restart" and the run fails. Other runs execute again once listeners are in place, before the Scheduler starts; Done steps keep their outputs.

Retry accepts a Failed or Cancelled run with no active run for its Workflow. It creates a new run from the original snapshot and input, with `retryOf` set; Done steps keep outputs and Sessions, the rest become Pending. It may repeat side effects of unfinished steps and is an explicit user action.

The Scheduler checks Workflow schedules as well as Routines; missed recurring slots fire once, overlapping scheduled runs are skipped, one-shot triggers disable after firing, and saving without changing the trigger or time zone keeps the next run. Routines and the Heartbeat keep their executor; migrating Routines to one-step graphs is follow-up work. Workflows have no Teams dependency.

## Agents

The `workflows` tool group has schedule's action interface: models, agents, list, get, validate, save, run, status, cancel, retry, delete. It is on by default, respects tool scope, and is not given to hidden Sessions. Save and validate default an omitted `agentId` to the caller and return every validation problem, one per line naming the step, edge or input, so the model can repair its graph. Validate checks without persisting and needs no Approval. The agents action lists ids and names, marking the caller; agentId uses an id, and unknown-Agent errors suggest up to 20 known ids. `models` lists available providers and models and the Workflow Agent default (the caller without a Workflow id). Reads need no Approval; changes always ask and cannot create Trust rules. Run and retry wait up to 120 seconds (60 by default), returning early when the user is needed. A Workflow's own Tool steps cannot run or retry Workflows, including through `run_code` and the tool catalog.

## Editor

The graph is edited in a hand-written HTML, CSS and JavaScript page on vendored Drawflow 0.0.60 (MIT), with no build step, shown in the app's Artifact web view (`SwingPanel` on desktop, `WebView` on Android, `WKWebView` on iOS). Kotlin inlines Drawflow and the page into one document in a script-only sandboxed frame that allows only nonce-marked scripts, no network and no `eval`. The browser build cannot receive page messages and shows a note instead.

The page is a view: its model is the protocol's `Workflow` JSON, never Drawflow's format. Steps are edited in their cards, with ids `<kind>-<n>` shown read-only, and prompt-like fields have an Insert… menu for ancestors' outputs, inputs and `{{now}}` parts. Connections drop anywhere on a card; a selected connection, or a tap on its input dot, offers Remove connection. The page refuses self and duplicate edges and leaves cycles to the Server. Steps without a position are laid out by longest-path depth. A given run shows each step's status and output.

The `WorkflowViewModel` is the single source of truth and the page never calls the Server. Messages (`WorkflowEditorMessages` in `client/app`) cross the Artifact bridge: `load`, `catalog`, `run` and `theme` in; `ready`, `changed` and `open_session` out. `changed` follows only user edits that alter the graph, and the ViewModel takes only `steps` and `edges` from it. The page reloads only on `load`, sent once ready and when the graph changes from outside the page. A change that only moves steps saves positions at once and never counts as unsaved. Everything else is native: list, name, description, inputs, trigger, enable switch, Agent, run values, Run, Retry and Cancel, the result, the bar of steps waiting on the user, save and discard, and run history. On narrow windows the native settings sit behind a "Settings and runs" bar, hidden except for a new Workflow.
