---
title: "Routine runs have their own strategy"
description: "Kinuvo routine runs have their own strategy."
---

Routine runs (Heartbeat included) used the chat strategy and told the user whatever the Agent replied unless it was exactly `[SILENT]`. They now run `routineStrategy`, a Koog graph of Gather → Work → Decide → Deliver. Gather writes the run's first message (when it runs, its schedule, how the last run ended, then the prompt); Work is chat's history/compaction/tool loop; Decide settles the outcome; Deliver saves it to the Session and notifies.

The Agent ends a run by calling `routine_report`: `report` with a short summary, or `nothing_new`. A run that never calls it reports its final reply, so a model without tool support still reaches the user. The outcome is a `SessionEntry.Outcome`; it decides whether the Session joins the chats, and the Scheduler keeps the report on the Routine (`lastReport`) for the next run's Gather.

Unattended runs wait for an Approval, then give up: a call unanswered for 30 minutes (`routineApprovalWait`, a constant of the Server) is denied with "No answer within 30 minutes; skipped", the run goes on, and skipped calls always reach the user in the report. `ask_user` still waits indefinitely.
