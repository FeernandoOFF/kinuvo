---
title: "Routines and Tasks"
description: "Scheduled prompts, the Heartbeat and work assigned through the Board."
---

A **Routine** is a prompt an Agent runs on a schedule: once, at an interval, or with a cron expression. Each run starts from the previous outcome and either reports something new with a Notification or stays silent when there is nothing new.

```sh
kinuvo -a default routines create morning "Review what needs my attention" --cron '0 9 * * MON-FRI' --tz Europe/Dublin
kinuvo -a default routines list
```

Use `--session new` for a fresh Session per run, or a Session ID to continue one. `--active-hours 08:00-22:00` limits when it fires. `--approvals inherit`, `auto` or `deny` controls calls needing an Approval. See [Approvals](approvals.md) for tolerance and the 30-minute unanswered-call limit; forms asking the user a question still wait indefinitely.

The **Heartbeat** is the Agent's built-in Routine. It works through `HEARTBEAT.md` and stays quiet when nothing needs you.

## Keep the Server available

The Scheduler runs inside the Server. Nothing fires while it is stopped. At startup, a missed recurring slot runs once if it is within its grace period: half the gap to the next slot, bounded to one minute through two hours. Older slots are skipped with a logged reason. An embedded Server's ability to keep running depends on the platform.

## Tasks and the Board

A Task is something you want done, held by a task provider such as Apple Reminders or the local provider. The **Board** shows status, assigned Agent and run Sessions.

An Agent assignment on the Board takes priority, then an `@agent` mention in the Task title or notes, then the list's configured Agent. Assigned open Tasks become ready when undated or within `tasks.startBeforeDays` of their due date. A finished run left in progress changes to waiting for you.

```sh
kinuvo tasks lists
kinuvo tasks add "Review the trip plan" --agent default
kinuvo tasks list
```

See [CLI commands](../reference/cli.md), [Server configuration](../reference/config.md), [ADR 0002](../adr/0002-in-process-scheduler.md) and [ADR 0015](../adr/0015-routine-runs-have-their-own-strategy.md).

<div class="k-future">

**Future enhancements**

- **More task providers** beyond Apple Reminders and the local provider.

</div>
