---
title: "In-process Scheduler that sleeps until the next Routine"
description: "Kinuvo in-process Scheduler that sleeps until the next Routine."
---

Routines are fired by one coroutine inside the server process: it sleeps until the soonest `nextRunAt` and is woken whenever a Routine changes, instead of a Hermes-style 60-second tick or an OS/cloud cron. Routines live in each Agent's `routines.json` until Sessions move to SQLite. Accepted cost: nothing fires while the server is down (the embedded desktop server only runs while the app is open). On start, a missed recurring slot runs once if it's within its grace period (half the gap to the next slot, 1 min–2 h) and is skipped with a logged reason otherwise. A scale-to-zero host would need an external trigger calling the same `run` path.
