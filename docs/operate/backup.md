---
title: "Back up your Server"
description: "Preserve the stopped Server home, external Knowledge and access dependencies."
---

Stop the Server before copying its home. A service must be stopped through its service manager so it does not restart during the copy. The usual home is `~/.kinuvo`; Docker uses `/data`, and `KINUVO_HOME` or checkout pins can select another path.

Copy the whole home to your backup location, including hidden files. It contains Agent configuration and documents, Sessions, Skills, Routines, MCP configuration, Task state, pairing state, Artifacts and attachments. Protect the backup as private data.

Also preserve:

- External Knowledge: the Markdown vault/folder or Anytype data is separate from the Server home when configured externally.
- Secret reference dependencies: access to the password manager and the required vaults.
- OS keychain dependencies: provider and Device credentials can live outside the home. A home copy alone does not transfer those entries or guarantee OAuth tokens can be decrypted on another machine.
- Deployment settings: service flags, image version, network/proxy settings and any external files needed by Exec backends.

Restore into a stopped home with the same intended version, confirm paths and access dependencies, then start one Server. Check Agents and Sessions, Knowledge access, provider sign-in and Device pairing. Reconnect integrations when their OS-bound credentials are unavailable.

There is no dedicated backup/restore CLI command. For checkout isolation and testing older versions, read [local testing](../local-testing.md). Sources: [file layout](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/core/src/store/AgentFiles.kt), [MCP OAuth storage](../adr/0007-mcp-oauth-server-owned-flow.md), [credential storage](../guides/public-hosting.md#credential-storage).
