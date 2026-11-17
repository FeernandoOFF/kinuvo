---
title: "Workflow conditions, inputs and retry"
description: "Branching graphs keep skipped work explicit and retry preserves finished results."
---

Status: Accepted

Conditions compare templated text without an Agent, model or Session. Equals and NotEquals trim text and compare numbers numerically when both parse. Contains and NotContains test substrings. Empty and NotEmpty treat blank text, null, [], {}, and false as empty. GreaterThan and LessThan require numbers. The output is true or false.

Only edges from Conditions can select a boolean branch; unlabelled edges always follow a Done source. A step waits for all predecessors to settle without failure, runs if any incoming edge is live, otherwise becomes Skipped. Roots run normally. Skipped outputs resolve as empty text or JSON null. Failure still cancels siblings. Recovery keeps terminal steps, including Skipped.

Each run stores declared string defaults overlaid with supplied JSON inputs, including undeclared keys. References can read the whole input or nested paths; missing input paths are empty text or JSON null. Step JSON may be wrapped in exactly one Markdown fence. A Done run exposes its Done sink output, or an object keyed by step id for several Done sinks, parsing each output as JSON where possible.

Retry accepts only Failed or Cancelled runs and requires no active run for that Workflow. It creates a new run from the original graph snapshot and effective input, with retryOf pointing to the original. Done StepRuns retain outputs and Sessions; all other steps become Pending, so branches are evaluated again. Retrying may repeat side effects of unfinished steps and is an explicit user action.
