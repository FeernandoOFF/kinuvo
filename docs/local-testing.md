---
title: "Isolated local testing"
description: "Kinuvo isolated local testing."
---

Use separate Git worktrees and data homes when testing incompatible versions.
Each checkout keeps its own ignored pins; switching to a bugfix branch inside that
checkout keeps the same data assignment. Choose a unique port for each Server,
including embedded desktop Servers, even if their bind addresses differ.

## Configure a checkout

Install mise and Python 3. From the checkout root:

```sh
mise trust
mise run init -- "$HOME/.kinuvo-experiment" 8790
mise run home
```

Initialization writes `.kinuvo-home` and `.kinuvo-port`; it does not create, copy,
or migrate application data, and refuses to overwrite existing pins. Back up an
existing home while its Server is stopped before testing a version that might
change its stored data. Never open experiment-modified data with an older version.

Keep machine-specific worktree paths, device assignments, backup locations and
operating notes in ignored `docs/local-testing.local.md`. Device assignments used
by Mockstro belong in ignored `.maestro/mockstro.local.yaml`.

## Run and manage

```sh
mise run desktop -- --compose-hot-reload
mise run server
mise run cli -- pair --manage
mise run android -- --device-id <android-serial>
mise run ios -- -p iosSimulatorArm64 --device-id <simulator-udid>
mise run ios -- -p iosArm64 --device-id <iphone-id>
mise run kotlin -- test -m app -p jvm
mise run exec -- <command> <args>
```

`mise tasks` lists the actions defined in `mise.toml`. Tasks run from their checkout
root, including when invoked from a subdirectory. They export
`KINUVO_HOME` and `KINUVO_EMBEDDED_PORT` from the pins, rejecting conflicting inherited
values. The standalone Server uses the pinned port as well. Run only one Server
per home: the `server` and `desktop` tasks check the advertised PID, but that check is not an
exclusive lock and cannot prevent simultaneous launches or PID reuse.

The mise tasks replace the former `./dev` launcher. Existing pins keep working;
run `mise trust` once in each existing checkout, without rerunning `init`.
Custom tasks that need the pinned environment can use `scripts/project-run.sh`.
Keep personal task overrides in ignored `mise.local.toml`.

Repository Mockstro and packaging entry points load the same pins when present.
The vendor-managed `./kotlin` wrapper is unchanged, so fresh-clone builds and CI
need no local configuration. In a pinned checkout, agents and project actions must
use `mise run kotlin -- ...` rather than invoking that wrapper directly. Directly launched
installed apps, jars and arbitrary scripts can bypass these guards. These rules
are launch conventions, not filesystem access controls.

Pins isolate Kinuvo's data and preferences, not external services, files or shared
Keychain/Codex credentials. Finish interactive device control before starting
Mockstro; see [.maestro/README.md](../.maestro/README.md) for the device workflow.

## Standalone services and mobile apps

An installed service runs its installed jar, not the source checkout. Build a new
Server with `./package-server.sh`, preserve the previous jar, stop the service,
replace the installed jar, then restart and verify it. Keep its existing home,
port and network configuration. macOS uses one launch-service label across data
homes; only its assigned checkout should manage it.

JVM diagnostics use kotlin-logging through SLF4J and Logback. Interactive commands
and the desktop app log to stderr, leaving CLI results on stdout. `KINUVO_LOG_LEVEL`
sets the minimum level (default `INFO`). Installed services use
`logback-service.xml` and `-Dkinuvo.log.file=<home>/logs/server.log`: the file rolls
daily or at 10 MB, with seven days of compressed archives capped at 100 MB.
Raw process output, including failures before logging starts, goes to
`logs/service-output.log`. `service logs` shows both files and `--follow` follows
their names across rotation. Reinstall existing service definitions after this
logging change so process output and the rolling file have separate paths.

Mobile apps use their own application containers. To keep two versions installed
with independent app data, use separate application IDs or separate devices.
Separate Server homes alone do not isolate mobile app installations. On iOS, the
background task identifier follows the app's bundle identifier. If builds register
the same URL scheme, enter pairing details manually to avoid opening the wrong app.
Use the Mac's reachable LAN or private HTTPS address when pairing a physical phone;
localhost on the phone refers to the phone itself.
