---
title: "Local testing"
description: "Choose personal or test data when launching Kinuvo."
---

## Desktop homes

No checkout initialization is required. Choose the desktop home when launching:

```sh
mise run desktop:personal
mise run desktop:test
mise run desktop:test -- --compose-hot-reload
```

`desktop:personal` uses `~/.kinuvo` on port 8787. `desktop:test` uses
`~/.kinuvo-test` on port 8791. Both forward arguments to the same desktop launcher,
including the packaged macOS app needed for Voice chat permissions.

Override each target independently in your shell or ignored `mise.local.toml`:

```sh
KINUVO_PERSONAL_HOME=/absolute/personal-data KINUVO_PERSONAL_PORT=8790 mise run desktop:personal
KINUVO_TEST_HOME=/absolute/test-data KINUVO_TEST_PORT=8791 mise run desktop:test
```

These tasks explicitly select their target even if `KINUVO_HOME` is inherited.
They do not copy or delete data. Homes isolate data and preferences, but not
external services, files, shared credentials, or OS capture/speech permissions.
Stop any Server using the selected home before launching another. Back up data
before opening it with an incompatible version. Separate ports allow the two
homes to run concurrently; never run multiple Servers against one home.

## Other tasks

Other tasks inherit the environment normally. `desktop` defaults to `~/.kinuvo`
and port 8787; `server` uses its normal CLI defaults and accepts `--port`.

```sh
KINUVO_HOME=/absolute/test-data mise run server -- --port 8791
KINUVO_HOME=/absolute/test-data mise run cli -- pair --manage
mise run android -- --device-id <android-serial>
mise run ios -- -p iosSimulatorArm64 --device-id <simulator-udid>
mise run kotlin -- test -m app -p jvm
mise run exec -- <command> <args>
mise run test:tasks
```

Tasks run from the checkout root and forward arguments without requiring home or
port files. The vendor-managed `./kotlin` wrapper is unchanged. Packaging and
Mockstro inherit the caller's environment. Historical `.kinuvo-home` and
`.kinuvo-port` files are no longer read; existing data directories are untouched.
Keep machine-specific operating notes in ignored `docs/local-testing.local.md`
and device assignments in ignored `.maestro/mockstro.local.yaml`. Use only devices
designated for this checkout and finish interactive device control before Mockstro;
see [.maestro/README.md](../.maestro/README.md).

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
