---
title: "Shared Screens for Computer and Browser use"
description: "Share Live view and Takeover rules across Computer displays and Browser tabs."
---

Status: Accepted

## Context

Computer displays and Browser tabs need the same authorization and Live view controls.

## Decision

Use one Screen contract with frame-bound input, control generations and bounded ephemeral delivery separate from durable responses and Approvals. Takeover is per Screen, remains suspended on disconnect and ends only on explicit handback. Browser uses continuous CDP capture; Computers expose capture refresh until native producers are implemented and verified. Restore JVM Browser tabs from saved safe URLs without replaying input or claiming to restore unsaved page state; held tabs restore suspended.

## Consequences

Semantic Agent captures remain independent of viewer frames. Browser uses ordinary Agent Risk, Trust and Deny rules without additional sensitive-field refusals; private HTTP(S) sites remain accessible. Breaking contract changes require matching Server and Device protocol versions.
