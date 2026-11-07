---
title: "Kotlin Toolchain (Amper) instead of Gradle"
description: "Use the Kotlin Toolchain for multiplatform builds and desktop Hot Reload."
---

Status: Accepted

## Context

The project needs multiplatform builds and fast desktop UI iteration.

## Decision

Build uses JetBrains' Kotlin Toolchain (alpha, `project.yaml`/`module.yaml`, `./kotlin` CLI) rather than Gradle, for built-in Compose Hot Reload on desktop.

## Consequences

Gradle plugins are unavailable; a module with a hard blocker may fall back to Gradle.
