---
title: "Start your first Server"
description: "Sign in to a model provider and run a foreground or background Server."
---

On the machine that will run the Server, sign in to the model provider:

```sh
kinuvo login
```

Open the address it prints and enter the device code. This signs in to a provider that supports device-code login; OpenAI API keys are configured separately in the app's model setup. See [models](../reference/models.md).

Start the Server in the foreground:

```sh
kinuvo
```

The default address is `127.0.0.1:8787`. Leave this terminal running. Stop it with Ctrl+C before starting another Server with the same home.

## Run in the background

On macOS or Linux, stop the foreground Server and install its user service:

```sh
kinuvo service install
kinuvo service status
```

The service starts at login and restarts if it exits. Linux can also start it before login using the `loginctl enable-linger` command printed by the installer. Windows does not have a service manager implementation. See [service management](../operate/service.md).

## Reach it from other Devices

For private HTTPS, install Tailscale on the Server machine and your Devices, enable MagicDNS and HTTPS in the tailnet, then use one of these:

```sh
kinuvo serve --tailscale
# Or, after stopping the foreground Server:
kinuvo service install --tailscale
```

`--tailscale` keeps the Server on loopback and configures Tailscale Serve. It cannot be combined with `--public-url`. See [browser access](../guides/browser-access.md) for prerequisites and ports, or [public hosting](../guides/public-hosting.md) for a public domain.

With the Server running, open another terminal and [pair a management Device](pair-devices.md):

```sh
kinuvo pair --manage
```

The usual home is `~/.kinuvo`; `KINUVO_HOME` selects another. In a development checkout, follow [isolated local testing](../local-testing.md) and use its pinned mise commands.
