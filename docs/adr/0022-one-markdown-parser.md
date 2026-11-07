---
title: "One Markdown parser"
description: "Share one Markdown parser across display, editing and native document projections."
---

Status: Accepted

## Context

Display, editing and future native views need consistent Markdown interpretation.

## Decision

Use JetBrains Markdown with its GFM flavour as the single parser. Compose renders the AST through multiplatform-markdown-renderer; the Composer edits source with shared formatting spans and operations. Expose an immutable MarkdownDocument projection for native consumers.

## Consequences

Rendering and editing share syntax semantics. Swift receives Objective-C-exported classes and typed arrays, so subtype switches need fallbacks; compiler-enforced Swift exhaustiveness and native rendering remain future work.
