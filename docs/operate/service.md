---
title: "Run a Server service"
description: "Manage the macOS launchd or Linux systemd user service."
---

The CLI supports a macOS launchd agent and a Linux systemd user service. Windows service management is not implemented.

## On macOS with the Kinuvo app

If the Kinuvo desktop app is in `/Applications` or `~/Applications`, `kinuvo service install` uses it: the app starts the Server at login with no window or Dock icon, so macOS shows Kinuvo (not Java) when it asks for permissions such as Calendar or Contacts. Nothing else changes: the same commands, home, logs and flags work.

- The first install may need your approval in System Settings → General → Login Items & Extensions. `kinuvo service status` shows "waiting for your approval" until you allow it.
- Opening the app while the service runs connects it to the service's Server instead of starting a second one. The service waits if the app's own Server is running, and starts when you quit the app.
- An older service installed without the app is replaced when you run `kinuvo service install` again.
- `--computer` isn't available with the app; use `kinuvo serve --computer`.
- macOS may ask for permissions again after you update the app, until releases use a Developer ID signature.

Without the app, the steps below install a launchd agent that runs the CLI's jar.

Stop a foreground Server using the same home, then install:

```sh
kinuvo service install
kinuvo service status
kinuvo service logs -n 100
```

Installation without the app copies the running jar to `<Server home>/bin/kinuvo.jar` and records its Java executable. Logs go to `<Server home>/logs/server.log`. The service starts at login and restarts on exit.

On macOS the label is `dev.kinuvo.server`; the jar-based agent keeps its plist in `~/Library/LaunchAgents/`. It starts at user login. On Linux the unit is `~/.config/systemd/user/kinuvo.service`; the installer prints a `loginctl enable-linger USER` hint if needed to start before login.

## Preserve deployment flags

```sh
kinuvo service install --tailscale
# Or a separately managed public HTTPS proxy:
kinuvo service install --public-url https://kinuvo.example.com
```

`--host` and `--port` set the listener. `--computer` shares an existing graphical desktop through cua-driver. `--tailscale-port` selects a Tailscale HTTPS port. Re-running installation updates the service, so pass its intended flags again, including after an upgrade.

```sh
kinuvo service restart
kinuvo service logs --follow
kinuvo service uninstall
```

Uninstall stops and removes the service; it does not delete the Server home or disable persistent Tailscale Serve routes. See [browser access](../guides/browser-access.md).

Only one Server should use a home. macOS has one service label across homes, so managing a different home does not create an independent launchd label. Development checkouts must follow their [assigned local-testing setup](../local-testing.md).
