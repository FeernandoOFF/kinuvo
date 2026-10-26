---
title: "Deterministic approvals by Risk tolerance"
description: "Kinuvo deterministic approvals by Risk tolerance."
---

Approvals were a yes/no per tool plus two-word shell prefixes, which asked for harmless commands and could be fooled by shell syntax. Now every tool call is assessed as Low, Medium or High Risk by plain code, and each Agent's Risk tolerance (strict, default, relaxed, full) decides which levels run without asking. The model has no say, so a prompt injection can't talk its way past an Approval (Vellum's model; Hermes and OpenClaw also ask an auxiliary LLM, which we leave out for now).

`shell` commands are parsed and classified per command: a table of read-only programs and their safe flags (Low), destructive programs and subcommands (High), and everything else Medium. What the parser can't follow (command substitution, subshells, heredocs, control flow, variable expansion, `eval`, `git -c`, inline code like `bash -c`) is High and can never be trusted, so ambiguity always asks. Trust rules match the parsed words, so `git push` never covers `git push --force`; High-risk commands are trusted only exactly, and a trusted command still asks when its redirects, `VAR=` assignments or outside paths exceed the tolerance. Deny rules, user globs plus a small built-in list (deleting `/` or home, fork bombs, formatting disks, piping downloads into a shell), are checked first and win even at `full` and in Routine Auto runs. Routines run relaxed, so only High-risk calls wait or are denied.

Accepted cost: the tables are conservative and incomplete, so unknown programs ask at `default` (relaxed lets them run), and parsing is not a sandbox: a Low program can still read secrets inside the Workspace, and a trusted script can do anything. The Workspace path check is textual. Real isolation is the container Exec backend. An LLM reviewer for Medium misses, limited to allow-once and never overriding a Deny rule, could come later.
