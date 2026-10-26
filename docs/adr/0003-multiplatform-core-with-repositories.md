---
title: "Multiplatform core with domain repositories over files"
description: "Kinuvo multiplatform core with domain repositories over files."
---

`harness/core` targets jvm, android and ios so the same engine can run on-device. Storage goes through domain repositories (`Store`, `AgentDocs`, `RoutineRepository`, `SkillRepository`, `TrustRulesRepository`) rather than a thin filesystem wrapper: domain code (`Agent`, Instructions, tools, `Routines`, `ApprovalPolicy`) never sees a path, and only the `store` package knows the `~/.kinuvo` layout (`AgentFiles`). The file implementations use kotlinx-io and live in common code; the few platform bits (symlink-safe delete, owner-only permissions, process environment) are `expect`/`actual`. This keeps the on-disk layout unchanged (no migration) and lets SQLite replace one repository at a time.

Repository methods are `suspend` and serialize writes with a `Mutex`. The lock isn't reentrant, so code holding it only calls the underlying storage, and writes that must survive a cancelled run (partial replies, stopped tool results, run-finished events) go through `NonCancellable`.

Time uses kotlinx-datetime. Cron matching walks local time: a slot the clocks skip runs shifted by the gap (01:30 runs at 02:30, as java.time resolves it), and a slot they repeat runs once. Accepted cost: a frequent cron (e.g. every 15 minutes) skips the repeated hour once a year.

Known limit: Agent lifecycle (create, clone, delete) and the Agent's display name are file-level in `FileStore`, because all of an Agent's data shares one folder. Once a repository moves off files, lifecycle moves to a coordinator above the repositories, each copying and deleting its own data.
