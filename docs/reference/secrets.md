---
title: "Secret references"
description: "Resolve configuration values from a password manager on the Server."
---

A Secret reference points to a password-manager field in `${…}` syntax:

```yaml
search:
  provider: firecrawl
  options:
    apiKey: ${pass://Kinuvo/Firecrawl/key}
```

The Server resolves the reference when its consumer needs it. Settings show the reference rather than the value. Resolved values are cached in memory for five minutes and are not written to config or sent to Devices or Agents. Environment variables use the same expansion syntax; an environment value can itself hold a Secret reference.

## Proton Pass

The implemented password-manager provider uses the `pass` URI scheme and requires `pass-cli` on the Server. Connect it with a Proton Pass AI agent access token using **Settings → Secrets** or:

```sh
kinuvo secrets connect
kinuvo secrets status
kinuvo secrets browse
```

Enter the token when prompted. The token grants read-only access to the vaults it covers. Reads carry a reason naming the consumer. The Server keeps the token in the OS keychain where supported and otherwise in the Server home's `.env` as `PROTON_PASS_PERSONAL_ACCESS_TOKEN`, rather than the child-inherited process environment.

```sh
kinuvo secrets refresh
kinuvo secrets disconnect
```

Refresh clears cached values so the next use reads the password manager again. Disconnect forgets the access token and signs out.

## Limits

MCP `command` and `args` refuse references; use environment values or HTTP headers. Exec passes configured values through environment or stdin so they do not appear as process arguments. A failed read fails the setting using it, such as an MCP server startup.

Tool output masks cached resolved values of at least four characters. This is not a guarantee against a consumer exposing a value: masking covers only values still in memory. Keep tool access and Workspaces appropriate.

Self-refreshing provider OAuth tokens, such as Codex sign-in, are separate. MCP OAuth is Server-owned storage too; see [MCP](../guides/mcp.md). Other password-manager URI schemes are not implemented just because their syntax exists.

Sources: [ADR 0005](../adr/0005-secret-references-resolved-by-the-server.md), [Secrets](https://github.com/FeernandoOFF/kinuvo/blob/main/harness/core/src/Secrets.kt).
