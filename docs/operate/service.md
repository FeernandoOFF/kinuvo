---
title: "Run a Server service"
description: "Manage the macOS launchd or Linux systemd user service."
---

The CLI supports a macOS launchd agent and a Linux systemd user service. Windows service management is not implemented.

Stop a foreground Server using the same home, then install:

```sh
kinuvo service install
kinuvo service status
kinuvo service logs -n 100
```

Installation copies the running jar to `<Server home>/bin/kinuvo.jar` and records its Java executable. Logs go to `<Server home>/logs/server.log`. The service starts at login and restarts on exit.

On macOS the label is `dev.kinuvo.server`, with a plist in `~/Library/LaunchAgents/`. It starts at user login. On Linux the unit is `~/.config/systemd/user/kinuvo.service`; the installer prints a `loginctl enable-linger USER` hint if needed to start before login.

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

Source: [ServiceCommands](https://github.com/FeernandoOFF/kinuvo/blob/main/apps/cli/src/ServiceCommands.kt).
