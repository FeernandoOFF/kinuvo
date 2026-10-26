---
title: "Troubleshooting"
description: "Check Server reachability, pairing, models and integrations."
---

## A Device cannot connect

Check that the Server is running and that the Device can reach its advertised address. For a service, use `kinuvo service status` and `kinuvo service logs`. A phone's localhost is the phone, not your Server. Native apps can use explicit private addresses; remote browsers need HTTPS.

If pairing fails, request a new code with `kinuvo pair --refresh --manage` on the Server machine. A code expires after ten minutes or one successful pairing. An expired or revoked Device must pair again. See [pairing](../start/pair-devices.md) and [browser troubleshooting](../guides/browser-access.md#troubleshooting).

## Tailscale or public HTTPS fails

For Tailscale, check `tailscale status`, MagicDNS, HTTPS certificates and permission to configure Serve. Check `tailscale serve status` for conflicting routes. For a public proxy, its HTTPS origin must exactly match the Server's public URL, including the port. See [browser access](../guides/browser-access.md) and [public hosting](../guides/public-hosting.md).

## A model cannot run

Check the provider's sign-in or API key on the Server, then refresh models in Settings. A cached listing is not proof of current account access. Apple availability depends on the Device and build. See [models](../reference/models.md).

## Tools are missing or waiting

Check the Agent's enabled tool groups, the Server's available plugins, and any required configuration. Exec needs at least one valid Workspace. A Device tool must be shared by that Device; a Computer must be enabled and reachable. Check the Session for an Approval or form waiting for you.

For MCP, run `kinuvo mcp test NAME`; if it needs sign-in, use `kinuvo mcp login NAME`. For Secret references, check `kinuvo secrets status` and refresh after changing a password-manager field. See [MCP](../guides/mcp.md), [Workspaces](../guides/workspaces.md) and [Computer use](../guides/computer-use.md).

## A Routine did not run

The Server must be running. Check that the Routine is enabled, its time zone and active hours, and the latest run Sessions with `kinuvo routines runs ID`. Missed recurring slots outside the startup grace period are skipped. A `nothing_new` outcome sends no Notification. See [Routines and Tasks](../concepts/routines-and-tasks.md).

## Gather evidence

Keep the relevant service log and Session error. Local Agent [traces](tracing.md) record timings and status without prompts or tool output. Record the installed version, platform and exact steps when reporting an issue; do not publish pairing links or private data.
