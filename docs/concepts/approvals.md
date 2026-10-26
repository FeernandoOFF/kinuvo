---
title: "Approvals and Risk"
description: "How Risk tolerance, Trust rules and Deny rules control tool calls."
---

An Approval is your decision on one tool call or Reflection proposal. For a tool call, you can allow it once, save an **always allow** Trust rule where offered, or deny it.

The harness classifies tool calls with code rather than asking the model:

| Risk | Meaning |
| --- | --- |
| Low | Reads |
| Medium | Changes things |
| High | Destructive or sensitive actions |

The Agent's Risk tolerance decides which calls run without an Approval. A Session can choose its own tolerance.

| Risk tolerance | Runs without asking |
| --- | --- |
| `strict` | Nothing |
| `default` | Low |
| `relaxed` | Low and Medium |
| `full` | All Risk levels, subject to Deny rules and tool-specific checks |

## Trust and Deny rules

Trust rules belong to one Agent or the whole Server. A tool rule matches its tool identity; a shell rule matches parsed command words. High Risk commands must match exactly. For example, trusting `git push` does not trust `git push --force`.

Deny rules are checked first and win over Risk tolerance and Trust rules. They can match tools or shell patterns. Built-in Deny rules block actions such as deleting the home folder, formatting disks and piping downloads into a shell.

## Shell commands

The parser checks each command, redirects, assignments and paths. Known read-only commands with safe flags can be Low; unknown programs are generally Medium. Syntax it cannot safely classify, such as command substitution, subshells, heredocs, control flow and inline interpreter code, is High and cannot be trusted.

Workspace checks and command classification are not OS isolation. A trusted script can do more than its name suggests. Use a container [Exec backend](../guides/workspaces.md) when you need isolation.

## Unattended work and Computers

Routines normally use relaxed Risk tolerance. Auto runs, and runs whose Agent is full, use full. An Approval unanswered for 30 minutes is denied for that call and the run continues with the skipped call in its report. Deny rules still apply.

Computer use has extra checks, including app-scoped Trust rules and actions that always need an Approval. Read [Computer use](../guides/computer-use.md#risk) before enabling it.

Source: [ADR 0006](../adr/0006-deterministic-approvals.md), [shell parser](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/core/src/ShellCommand.kt).
