---
title: "CLI reference"
description: "Commands and options implemented by the kinuvo CLI."
---

The CLI manages the Server on its own machine. `kinuvo` alone runs a Server. Most management commands connect to the running Server, or briefly start a local Server without scheduling Routines when none is running. Pairing and Device commands require a running Server.

Global options: `-a, --agent ID` selects an Agent (also `KINUVO_AGENT`; defaults to the first Agent), `--version` prints the version, and `--help` shows help. Place Agent selection before the command. `KINUVO_HOME` selects the data home. See [mise commands](../local-testing.md) for personal and test desktop homes.

## Server and login

| Command | Options / arguments |
| --- | --- |
| `kinuvo serve` | `--host` (default `127.0.0.1`), `--port` (default `8787`), `--public-url`, `--trusted-proxy` (comma-separated IPs), `--computer`, `--tailscale`, `--tailscale-port` (default `443`) |
| `kinuvo login [PROVIDER]` | Device-code sign-in; omit provider when only one supports login |

`--tailscale` and `--public-url` are alternatives. See [browser access](../guides/browser-access.md) and [public hosting](../guides/public-hosting.md).

## Service

Supported on macOS (launchd) and Linux (systemd user service).

| Command | Options |
| --- | --- |
| `service install` | `--host`, `--port`, `--public-url`, `--computer`, `--tailscale`, `--tailscale-port`, `--jar PATH`, `--force` |
| `service status` | Show installed service, Server and log path |
| `service restart` | Restart the installed service |
| `service logs` | `-f, --follow`; `-n, --lines` (default `100`) |
| `service uninstall` | Stop and remove the service |

Install uses the running jar by default and copies it to `<home>/bin/kinuvo.jar`. `--force` bypasses the other-Server check; stop any Server using that home first. See [service management](../operate/service.md).

## Devices

| Command | Options / arguments |
| --- | --- |
| `pair` | `--manage`, `--refresh`, `--host ADDRESS` |
| `devices` or `devices list` | List paired Devices |
| `devices revoke DEVICE` | ID or unambiguous name |

## Agents

| Command | Options / arguments |
| --- | --- |
| `agents list` | List Agents |
| `agents show ID` | Settings, Identity and Soul |
| `agents create ID` | `--name NAME`, `--clone FROM` |
| `agents set ID KEY VALUE` | Keys: `name`, `model`, `reasoning`, `approvals`, `exec`, `workspace`, `tools`, `identity`, `soul`; comma-separated Workspace/tools; `-` reads stdin |
| `agents delete ID` | `-y, --yes` skips confirmation; deletes its Sessions, Memory, Skills and Routines |

IDs use lowercase letters, digits and hyphens. The legacy `profile` alias routes to `agents`.

## Routines

These act on the selected Agent. The legacy `cron` alias routes to `routines`.

| Command | Options / arguments |
| --- | --- |
| `routines list` | List schedules and state |
| `routines show ID` | Show a Routine |
| `routines create NAME PROMPT` | One of `--at`, `--every`, `--cron`; schedule options below; `--paused` |
| `routines edit ID` | `--name`, `--prompt`, and schedule options; omitted options stay unchanged |
| `routines enable ID` / `routines disable ID` | Turn it on or off |
| `routines run ID` | Run now without changing the schedule |
| `routines remove ID` | Delete it; the Heartbeat is turned off instead |
| `routines runs ID` | List its Sessions, newest first |

Schedule options: `--at 2026-11-01T09:00`, `--every 30m` (also `2h`, `1d`), `--cron '0 9 * * MON-FRI'`, `--tz Europe/Dublin`, `--active-hours 08:00-22:00` (`none` clears), `--session new` or a Session ID, `--approvals inherit|auto|deny`. A prompt of `-` reads stdin.

## Skills

| Command | Options / arguments |
| --- | --- |
| `skills list` | List available Skills |
| `skills show NAME` | `--shared` selects the shared copy |
| `skills create NAME` | Required `--description`, `--body` (`-` reads stdin); optional `--shared` |
| `skills install PATH` | Folder or `SKILL.md`; `--shared` |
| `skills remove NAME` | `--shared` |

## MCP

| Command | Options / arguments |
| --- | --- |
| `mcp list` | List MCP servers available to the selected Agent |
| `mcp add NAME` | `--url URL` or `--command COMMAND`; arguments after `--`; options below |
| `mcp remove NAME` | `--shared` |
| `mcp test NAME` | Connect and list tools |
| `mcp login NAME` | `--shared`, `--no-browser` |
| `mcp logout NAME` | `--shared` |

Add options: repeatable `--env KEY=VALUE`, `--header KEY=VALUE`, `--include TOOL`, `--exclude TOOL`, and `--oauth-scope SCOPE`; `--approval auto|ask`, `--client-id`, `--client-secret`, `--shared`. Without an approval override, Risk follows tool hints. See the [MCP guide](../guides/mcp.md) for OAuth and examples.

## Knowledge

| Command | Options / arguments |
| --- | --- |
| `knowledge search QUERY` | `--type KEY` filters the native Note type |
| `knowledge show REF` | Note reference or title |
| `knowledge changes` | `-n, --limit` (default `20`) |
| `knowledge undo ID` | Full change ID or its first characters |

## Tasks

| Command | Options / arguments |
| --- | --- |
| `tasks list` | `--all` includes done Tasks |
| `tasks lists` | Lists shown on the Board and assigned Agents |
| `tasks add TITLE` | `--list`, `--due YYYY-MM-DD`, `--notes`, `--agent ID` |
| `tasks assign ID AGENT` | `none` unassigns |
| `tasks status ID STATUS` | `todo`, `in-progress`, `waiting`, `done` |

## Secret references and Notifications

| Command | Options / arguments |
| --- | --- |
| `secrets status` | Show password managers and connection state |
| `secrets connect` | Prompt for a token or read piped agent instructions; `--scheme` defaults to `pass` |
| `secrets disconnect` | `--scheme pass` |
| `secrets browse [VAULT [ITEM]]` | Names only; `--scheme pass` |
| `secrets refresh` | Clear the in-memory resolved-value cache |
| `notify [MESSAGE]` | Test the configured Notification service |

Source: [CLI command implementations](https://github.com/FeernandoOFF/kinuvo/tree/main/apps/cli/src). This reference is derived from the Clikt definitions; no CLI command was run against a data home to generate it.
