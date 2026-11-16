---
title: "Workflow Tool and Model steps"
description: "Workflow steps that make one tool call or one model request without a Session, run under a Workflow-level Agent."
---

Status: Accepted

A Workflow step is an Agent turn, an Approval, a Tool call or a Model request. A Tool step makes one call to any tool the Agent could call (Server, plugin and MCP tools, `run_code`, `show_ui`) with templated JSON arguments and no model. A Model step makes one model request with no tools; its text reply is the output. Neither creates a Session, so proven work can run as cheap deterministic steps.

Both run under the Workflow's `agentId`, the Agent it runs as, which is required when a Workflow has either kind and must be an existing Agent. A Tool call passes through the same gate as a run: the Agent's Tool scope, Deny and Trust rules, Approvals, redaction and limits. An unknown or refused tool fails the step with the refusal as its error. A Model request uses the Agent's provider and model unless the step names another; its only context is the step's `system` text, with nothing from the Agent's personal context, Memory or Skills. Its transcript is discarded, and it never appears in Session listings.

A Session exists only when a Tool call needs an Approval. The step creates one then, publishes the pending interaction there, answered through `session.respond` like any other, and returns to running once it is answered. Like an Approval step, it cannot create Trust rules. Approval steps also publish their Session under the Workflow's `agentId` when it is set.

Step outputs stay strings. `{{steps.<id>.output}}` inserts a step's output; `{{steps.<id>.output.<key or index>...}}` parses the output as JSON and resolves the path, inserting a string result as is and anything else as compact JSON. Output that is not JSON, or a path that is missing, fails the step naming the reference. References work in Agent turn prompts, Model `prompt` and `system`, and every string value inside Tool `args`. An `args` string that is exactly one reference becomes the referenced JSON value itself, so objects, arrays and numbers keep their type. Every reference must name an ancestor step.
