---
title: "Install Kinuvo"
description: "Install the Server and CLI, run Docker, or download a Device app."
---

One Server owns your Agents and their data. Install it on a machine you want to keep available, or use the embedded Server offered by an app. Other Devices connect to that Server.

## Server and CLI

The installer bundles Java. It supports Linux, Apple silicon Macs and Windows.

macOS and Linux:

```sh
curl -fsSL https://raw.githubusercontent.com/FeernandoOFF/kinuvo/main/install.sh | sh
```

Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/FeernandoOFF/kinuvo/main/install.ps1 | iex
```

Follow the installer's PATH instructions, then [start your first Server](first-server.md). Run the installer again to upgrade. Set `KINUVO_VERSION` to a release tag to select a version; see [upgrading](../operate/upgrade.md).

## Docker

```sh
docker run -d --name kinuvo -p 127.0.0.1:8787:8787 -v kinuvo:/data ghcr.io/feernandooff/kinuvo
docker exec -it kinuvo kinuvo login
docker exec -it kinuvo kinuvo pair --manage
```

The image contains the Server, CLI and web app. The named volume keeps the Server home at `/data`. The example publishes the port only on the Docker host's loopback interface. For access from other machines, configure [browser access](../guides/browser-access.md) or [public hosting](../guides/public-hosting.md).

## Device apps

Download the desktop app (`.dmg` for Apple silicon Macs, Linux `.deb`, Windows `.zip`) or Android `.apk` from [Releases](https://github.com/FeernandoOFF/kinuvo/releases/latest). The Server includes the web app; it does not need a separate installation. iOS development instructions are in the [iOS README](https://github.com/FeernandoOFF/kinuvo/blob/main/apps/ios/README.md); the release workflow does not publish an iOS app.

The desktop apps are unsigned. On macOS, open the app once, then allow it under **System Settings → Privacy & Security → Open Anyway**. On Windows, choose **More info → Run anyway** in SmartScreen. See [releasing](../releasing.md#unsigned-macos-and-windows-apps).

Next: [start a Server](first-server.md), [pair Devices](pair-devices.md), or [set up your first Agent](first-agent.md).
