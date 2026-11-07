---
title: "Support"
description: "Get help with Kinuvo, Server setup, permissions and data deletion."
---

> Draft for release review. Store download links will be added after the testing tracks are available.

Kinuvo is a free, self-hosted personal AI assistant by Fernando Obregon Fernandez. Contact **me@feernandooff.com** for support or privacy requests. The app is free; external AI providers, hosting and connected services may charge separately.

## Start using Kinuvo

You can use the on-device Server or connect to your own remote Server. No hosted Kinuvo account is required. Available tools depend on the selected Server, Device and model.

- [Install a Server](start/install.md), [start it](start/first-server.md), and [pair a Device](start/pair-devices.md).
- On iOS, Apple's on-device model requires a supported Apple Intelligence device, enabled Apple Intelligence and downloaded model assets. If unavailable, use a configured external model or remote Server. See the store listing for supported operating systems and Devices.
- Android supports downloaded on-device models through LiteRT-LM on compatible hardware. Choose Settings → Model providers → On this device. Alternatively configure an external provider or compatible endpoint. Model downloads need internet access and storage; inference then runs on the selected Server.
- Connect your own provider account or API key under the available model settings. Third-party eligibility, regional restrictions and charges apply.

## Report a problem

Email your platform, app version/build, Device/OS version, whether the Server is on-device or remote, and the steps that caused the issue. Explain what you expected and what happened. Use a small, redacted example. Do not include passwords, pairing codes, API keys, private screenshots or a full Server backup.

For offensive or unsafe AI output, you can contact support using the same address.

## Connection and permission help

A remote Server address must be reachable from your phone. `localhost` on a phone means that phone, not your computer. Pairing codes expire and can be used once; request a new code if needed. Check network access and whether the Server is running before pairing again.

Grant Contacts, microphone or camera access only when you want to use the relevant feature. Revoke access in your system settings and disable the tool in Kinuvo. The Google Play build does not offer Android Accessibility Computer use. Background availability differs by platform; iOS is not an always-running scheduled Server.

## Delete data or disconnect

Kinuvo has no publisher-hosted user account to delete. Disconnecting a remote Server or revoking a Device removes access, not content on that Server. Delete individual items using the available controls. Session deletion does not erase every related attachment, Memory, Artifact, external record or backup.

For complete deletion, ask your Server operator to stop and remove its data, or remove your on-device app data using platform controls. Separately revoke provider tokens, remove retained keychain entries where applicable, and delete external service copies or backups. See the [privacy policy](privacy.md) and [Server backup guide](operate/backup.md).

To remove support correspondence held by the publisher, email **me@feernandooff.com**. Support correspondence and voluntarily supplied diagnostics are deleted within 90 days after resolution unless a legal obligation requires retention.
