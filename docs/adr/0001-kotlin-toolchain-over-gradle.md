---
title: "Kotlin Toolchain (Amper) instead of Gradle"
description: "Kinuvo kotlin Toolchain (Amper) instead of Gradle."
---

Build uses JetBrains' Kotlin Toolchain (alpha, `project.yaml`/`module.yaml`, `./kotlin` CLI) rather than Gradle, to experiment with it and get built-in Compose Hot Reload on desktop. Accepted cost: no Gradle plugins (e.g. SQLDelight is unavailable). If a module hits a hard blocker, fall back to Gradle for that module only.
