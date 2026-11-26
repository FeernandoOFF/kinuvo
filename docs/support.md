---
title: "Support"
description: "Get help with Kinuvo, Server setup, permissions and data deletion."
---

Kinuvo is a free, self-hosted personal AI assistant by Fernando Obregon Fernandez. Contact **me@feernandooff.com** for support or privacy requests. The app is free; external AI providers, hosting and connected services may charge separately.

## Start using Kinuvo

You can use the on-device Server or connect to your own remote Server. No hosted Kinuvo account is required. Available tools depend on the selected Server, Device and model. On Android, “Reach this Server from other Devices” allows Devices on your Wi-Fi or Tailscale network to pair with the on-device Server and access its data. Use trusted networks and revoke unwanted Devices; LAN connections may be unencrypted.

- [Install a Server](start/install.md), [start it](start/first-server.md), and [pair a Device](start/pair-devices.md).
- On iOS, Apple's on-device model requires a supported Apple Intelligence device, enabled Apple Intelligence and downloaded model assets. If unavailable, use a configured external model or remote Server. See the store listing for supported operating systems and Devices.
- Android supports downloaded on-device models through LiteRT-LM on compatible hardware. Choose Settings → Model providers → On this device. Alternatively configure an external provider or compatible endpoint. Model downloads need internet access and storage; inference then runs on the selected Server.
- Connect your own provider account or API key under the available model settings. Third-party eligibility, regional restrictions and charges apply.

## Report a problem

Email your platform, app version/build, Device/OS version, whether the Server is on-device or remote, and the steps that caused the issue. Explain what you expected and what happened. Attach the ZIP from [Share your logs](#share-your-logs) if you can. Use a small, redacted example. Do not include passwords, pairing codes, API keys, private screenshots or a full Server backup.

For offensive or unsafe AI output, you can contact support using the same address.

## Share your logs

When something breaks, logs and crash files usually say why. Open **Settings → About → Share logs**. Kinuvo zips this Device's logs and crash files into one file and hands it to you: Android and iOS open the share sheet, and the desktop app shows the ZIP in your file manager (it is saved in `<Kinuvo home>/exports/`, by default `~/.kinuvo/exports/`). Open the ZIP, look through it, then attach it to your email or issue. Nothing is uploaded automatically.

If the app crashed last time, Kinuvo offers to share logs when it next opens. You can decline and share later from Settings.

The ZIP holds:

- `logs/`: recent events from the app and, on a phone or the desktop app, its own Server: when runs start and finish and how long they took, slow plugins, connection and Server connect/disconnect events, Session sync gaps, model-provider and MCP sign-in or connection errors.
- `crashes/`: the stack traces of recent crashes.
- `system.json`: the app version and build, platform, OS version and Device model.

It never includes your chats or message text, attachments, files, notes, Memory, configuration, databases, API keys, tokens or passwords. Secrets, e-mail addresses and the query strings of URLs are removed as lines are written and again when the ZIP is made, and user names are removed from file paths. Redaction is best effort: error messages from other services can still contain details, so review the files before sharing.

Logs are kept on your Device in up to four files of about 1 MiB per process, newest first; older lines are dropped. The ZIP has the newest part of each file, up to about 6 MiB in total. The last ten crashes are kept. On Android the files live in the app's private storage and on iOS in its Caches folder; the desktop app and headless Servers use `<Kinuvo home>/logs/` and `<Kinuvo home>/crashes/`. A Server you run on another computer keeps its own logs there, so share those files from that computer.

A sudden termination by the operating system, such as being stopped for memory, may leave no crash file.

## Connection and permission help

A remote Server address must be reachable from your phone. `localhost` on a phone means that phone, not your computer. Pairing codes expire and can be used once; request a new code if needed. Check network access and whether the Server is running before pairing again.

Grant Contacts, microphone or camera access only when you want to use the relevant feature. Revoke access in your system settings and disable the tool in Kinuvo. The Google Play build does not offer Android Accessibility Computer use. Background availability differs by platform; iOS is not an always-running scheduled Server.

## Delete data or disconnect

Kinuvo has no publisher-hosted user account to delete. Disconnecting a remote Server or revoking a Device removes access, not content on that Server. Delete individual items using the available controls. Session deletion does not erase every related attachment, Memory, Artifact, external record or backup.

For complete deletion, ask your Server operator to stop and remove its data, or remove your on-device app data using platform controls. Separately revoke provider tokens, remove retained keychain entries where applicable, and delete external service copies or backups. See the [privacy policy](privacy.md) and [Server backup guide](operate/backup.md).

To remove support correspondence held by the publisher, email **me@feernandooff.com**. Support correspondence and voluntarily supplied diagnostics are deleted within 90 days after resolution unless a legal obligation requires retention.
