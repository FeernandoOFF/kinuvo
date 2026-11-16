---
title: "Workflows"
description: "Chain fetches, small model requests, reviews and Agent turns into a graph the Server runs, and try the bundled examples."
---

A **Workflow** is a graph of steps the Server runs. A step starts when the steps before it are done, and steps with nothing between them run side by side. Each step can use what an earlier one produced by writing `{{steps.tool-1.output}}`, or a part of it with `{{steps.tool-1.output.title}}` when that output is JSON. A Workflow runs as one Agent, set by its `agentId`.

The point is cost and control. A large model works a task out once in a chat. The parts that proved reliable then run as Tool steps, which cost no tokens, and small Model steps, and you check the result in a Review before anything continues.

| Step | What it does |
| --- | --- |
| Tool | Makes one tool call with fixed arguments, no model and no Session. A tool that needs an Approval asks for one as usual. |
| Model | Makes one model request with no tools and no Session. Its reply is the output. |
| Review | Shows data as a form in a Session and continues with what you submit. A JSON object becomes one editable field per key; any other text becomes one text box. |
| Approval | Waits for you to allow the Workflow to continue. |
| Agent turn | Starts a Session where the Agent works on a prompt, so the result lands in a chat. |

Tool arguments that are exactly one reference keep their type, so `"input": "{{steps.review-1.output.refund}}"` passes a number. The `run_code` tool takes an `input` of any JSON value, available to its code as the variable `input`. A `run_code` step that calls no other tools returns its value as JSON text, or as plain text if it returns a string. `web_fetch` returns `HTTP 200` and a blank line before the page, and cuts long pages at 20,000 characters.

You can build Workflows in the app's editor. The examples below are plain JSON files.

## Examples

All of them run as the Agent `default`.

| File | What it shows |
| --- | --- |
| [front-page-digest.json](../examples/workflows/front-page-digest.json) | Every kind in one chain: fetch the Hacker News front page, cut it down to titles, links and points with a script, have a model write a digest, edit it in a Review, then hand it to an Agent turn. The fetch and the reduction cost no tokens. |
| [extract-review-fill.json](../examples/workflows/extract-review-fill.json) | No Session exists until the Review. A script supplies sample emails, a model pulls fields out of one as JSON, you correct them as a typed form, and a script fills a reply template from your answers. |
| [two-sources-one-answer.json](../examples/workflows/two-sources-one-answer.json) | Two fetches run in parallel, a model merges them, an Approval gates the next step, and an Agent turn delivers the briefing. |

The examples fetch public pages, which needs the `web_fetch` tool on the Agent. If a web reader such as Firecrawl is set up, `web_fetch` returns Markdown instead of JSON, and the digest example's script will fail; the first step then needs to be changed.

## Import and run

`import` creates the Workflow on the Server; `--replace` updates one that already exists. Give `-a AGENT` to run every step as that Agent. Without it, an Agent the Server does not have is replaced by the first Agent.

```sh
kinuvo -a default workflows import docs/examples/workflows/extract-review-fill.json
kinuvo workflows run extract-review-fill
kinuvo workflows runs extract-review-fill
```

`run` starts the run and prints its id without waiting. A Review or Approval appears as a question in a new Session in the app. `runs` shows the latest runs and each step's status. To use another Server home, set `KINUVO_HOME` for each command:

```sh
KINUVO_HOME=/absolute/test-data kinuvo -a default workflows import extract-review-fill.json
```

`workflows show ID` prints a Workflow as JSON that `import` reads back, which is a convenient way to copy and change one. `workflows list` and `workflows remove ID` do the rest. See [CLI commands](../reference/cli.md), [ADR 0036](../adr/0036-workflows.md), [ADR 0040](../adr/0040-workflow-tool-and-model-steps.md) and [ADR 0042](../adr/0042-workflow-runs-resume-after-restart.md).
