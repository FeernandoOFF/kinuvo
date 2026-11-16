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

Email your platform, app version/build, Device/OS version, whether the Server is on-device or remote, and the steps that caused the issue. Explain what you expected and what happened. Use a small, redacted example. Do not include passwords, pairing codes, API keys, private screenshots or a full Server backup.

For offensive or unsafe AI output, you can contact support using the same address.

## Connection and permission help

A remote Server address must be reachable from your phone. `localhost` on a phone means that phone, not your computer. Pairing codes expire and can be used once; request a new code if needed. Check network access and whether the Server is running before pairing again.

Grant Contacts, microphone or camera access only when you want to use the relevant feature. Revoke access in your system settings and disable the tool in Kinuvo. The Google Play build does not offer Android Accessibility Computer use. Background availability differs by platform; iOS is not an always-running scheduled Server.

## Delete data or disconnect

Kinuvo has no publisher-hosted user account to delete. Disconnecting a remote Server or revoking a Device removes access, not content on that Server. Delete individual items using the available controls. Session deletion does not erase every related attachment, Memory, Artifact, external record or backup.

For complete deletion, ask your Server operator to stop and remove its data, or remove your on-device app data using platform controls. Separately revoke provider tokens, remove retained keychain entries where applicable, and delete external service copies or backups. See the [privacy policy](privacy.md) and [Server backup guide](operate/backup.md).

To remove support correspondence held by the publisher, email **me@feernandooff.com**. Support correspondence and voluntarily supplied diagnostics are deleted within 90 days after resolution unless a legal obligation requires retention.

## Export diagnostics

In the desktop app, open **Settings → Help & diagnostics → Export diagnostics**.
Save the ZIP, open it and review the files before attaching it to an issue or email.
Known credentials and your home-folder path are redacted on a best-effort basis;
logs can still contain private information. Nothing is uploaded automatically.

The export includes recent operational logs, crash details, Agent trace metadata,
app version, OS and Java information. It does not copy your chats, attachments,
configuration, database or memory dumps. Debug logs and exception messages may
contain private details. Native JVM crash reports are reduced to their header
and stack frames. A sudden termination or OS kill may leave no crash report.

This Device's diagnostics include its local Server. To include a selected remote
Server, enable **Include Server diagnostics**; this requires management access.
Server export and debug recording are currently supported by JVM Servers.
Diagnostic export from the app is currently supported on desktop; mobile and web
apps can still report problems using the information above.

For intermittent problems, choose **Record debug logs**, reproduce
the problem, then stop recording and export. Recording ends automatically, lasts
at most 30 minutes and applies to JVM processes using the same Kinuvo home.

If the app will not open, use the CLI with the same `KINUVO_HOME`:

```sh
kinuvo diagnostics status
kinuvo diagnostics record 15
kinuvo diagnostics record 0
kinuvo diagnostics export --output diagnostics.zip
```

Desktop and CLI logs are stored in `<Kinuvo home>/logs/`, and crash details in
`<Kinuvo home>/crashes/`. On Windows the default home is `%USERPROFILE%\.kinuvo`.
Each process keeps up to three log files of about 4 MiB. Old process logs are
pruned at startup, retaining up to 24 files from stopped processes or seven days;
crash files retain up to 12 reports or seven days. Existing service log retention
is separate. JVM fatal reports are captured where the installed JVM permits it.

## Ask AI to triage a problem

```sh
kinuvo triage "The Windows app closes when I open Settings"
kinuvo triage "Pairing fails" --report diagnostics.zip --provider codex --model gpt-5.6-sol
```

Triage collects bounded, redacted diagnostic excerpts and saves `context.txt` for
you to review. It asks before sending that text and your description to the chosen
model provider; that provider's data handling applies. It uses your existing model
provider sign-in or API key. Use `--provider` and `--model` to choose another model.
Triage does not require a running Server and has no shell or other tools.

The result is an editable `issue.md`, with observed evidence separated from
possible causes. AI can make mistakes: check the draft before publishing it.
The command prints its saved path and a publishing command:

```sh
kinuvo triage --publish /path/to/issue.md
kinuvo triage --publish /path/to/issue.md --repo owner/repository
```

Publishing requires the GitHub CLI (`gh`) installed and signed in with access to
create issues in the repository. The default repository is `FeernandoOFF/kinuvo`.
The command shows the issue and asks before creating it; visibility follows the
repository. It publishes the draft text, not the ZIP or full diagnostic context.
Use `--create-issue` during triage to offer publishing immediately after drafting.
`--yes` skips prompts: with `--create-issue` or `--publish` it also authorizes
publishing. Without those options, `--yes` only authorizes the model request.
