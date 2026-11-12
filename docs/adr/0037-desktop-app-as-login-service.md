---
title: "Desktop app as the macOS login service"
description: "Kinuvo.app runs the Server at login so macOS privacy permissions attach to a stable bundle identity."
---

Status: Accepted

## Decision

On macOS, when `Kinuvo.app` (`dev.kinuvo.desktop`) is installed, `kinuvo service install` registers the launch agent bundled inside it (`Contents/Library/LaunchAgents/dev.kinuvo.server.plist`) with `SMAppService`. The agent runs `Kinuvo --serve`, which starts only the Server: headless AWT, no window or Dock icon, the same `serve` code as the CLI (`kinuvo.server.jvm.serve`, with Tailscale route setup). macOS attributes privacy permissions (TCC) to a bundle identity, so Calendar, Reminders and Contacts access given to the Server can belong to Kinuvo rather than a bare `java`. Without the app, the java launchd agent is unchanged. Linux is untouched.

The agent plist is fixed inside the signed bundle, so the home and listener flags cannot go in its arguments. `service install` writes them to `~/Library/Application Support/Kinuvo/service.json` (`LoginServiceSettings`), which `--serve` reads; it also sets `kinuvo.home`, which `kinuvoHome()` honors before `$KINUVO_HOME`. The plist sets only a common `PATH`. Logs keep the same files: `--serve` points Logback at `<home>/logs/server.log` and redirects stdout and stderr to `service-output.log`. `--computer` is not supported by this service yet.

`SMAppService` answers only to the app's own process, so the CLI runs `Kinuvo --service status|register|unregister`, which calls a Swift `@_cdecl` dylib (`libKinuvoService.dylib`, built by `scripts/build-mac-service.sh`) through FFM, like the speech and web dylibs. The CLI finds the app through `KINUVO_APP`, `/Applications`, `~/Applications`, then LaunchServices, and requires the bundled agent. `restart` and the running state use `launchctl` on the same label. Installing again replaces the registered agent so it starts with the new settings.

The label stays `dev.kinuvo.server`, so installing over an old-style agent first boots it out and removes its plist. macOS reports an old-style agent with the same label as registered, so the app's service counts as installed only once that plist is gone.

## One Server per home

The windowed app already joins a live Server recorded in the home's `server.json`. Two more cases are closed:

- The app starts while a registered service has not yet written `server.json` (both launch at login). When the settings name the app's home and the agent is enabled, the app waits up to 20 seconds for that Server before starting its embedded one.
- The service starts while the app's embedded Server holds the home. `--serve` waits, polling every 5 seconds, then starts once that Server stops, rather than exiting and being restarted in a loop. The check is not atomic; two processes starting within the same instant could still both listen.

## Consequences

Registration may need the owner's approval in System Settings → General → Login Items & Extensions; `service status` and `install` say so. The CLI starts a JVM for each `--service` call (about a second).

Permissions persist across updates only with a stable code-signing identity. Release builds are ad hoc signed, so macOS may ask again after each update; a Developer ID signature is future work and out of scope here, as are the EventKit and Contacts tools this prepares. A Computer for the service and a "run at login" toggle in the app are also future work.
