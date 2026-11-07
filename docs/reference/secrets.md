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

Refresh clears cached values so the next use reads the password manager again. Disconnect forgets the access token and signs out. The `connect`, `browse` and `disconnect` commands take `--scheme` (`pass` by default) to pick the password manager; several can be connected at once.

## 1Password

The `op` scheme uses 1Password's own secret references, `${op://Vault/Item/field}` or `${op://Vault/Item/section/field}`, and requires the 1Password CLI (`op`) on the Server. Connect it with a service account token (it starts with `ops_`):

```sh
kinuvo secrets connect --scheme op
kinuvo secrets browse --scheme op Kinuvo GitHub
```

Create the service account under **Developer → Service accounts** with read-only access to only the vaults Kinuvo may use. Service accounts can't reach Personal or Private vaults, so keep Kinuvo's items in a shared vault. The token is kept in the OS keychain, otherwise in the Server home's `.env` as `OP_SERVICE_ACCOUNT_TOKEN`. `op` keeps its configuration in the Server home's `1password/` folder, apart from yours.

## Bitwarden Secrets Manager

The `bws` scheme reads Bitwarden Secrets Manager (not the password vault) through the `bws` CLI. A reference names a project and a secret's key, `${bws://Project/KEY}`, or a secret's id, `${bws://<id>}`. Connect it with a machine account access token:

```sh
kinuvo secrets connect --scheme bws
kinuvo secrets browse --scheme bws Kinuvo
```

Create the machine account under **Secrets Manager → Machine accounts** with read access to only the projects Kinuvo may use. The token is kept in the OS keychain, otherwise in the Server home's `.env` as `BWS_ACCESS_TOKEN`. Set `BWS_SERVER_URL` in that `.env` for the EU region (`https://vault.bitwarden.eu`) or a self-hosted server. Secret keys need to be unique within a project to be referred to by key.

## Limits

MCP `command` and `args` refuse references; use environment values or HTTP headers. Exec passes configured values through environment or stdin so they do not appear as process arguments. A failed read fails the setting using it, such as an MCP server startup.

Tool output masks cached resolved values of at least four characters. This is not a guarantee against a consumer exposing a value: masking covers only values still in memory. Keep tool access and Workspaces appropriate.

Self-refreshing provider OAuth tokens, such as Codex sign-in, are separate. MCP OAuth is Server-owned storage too; see [MCP](../guides/mcp.md). Other password managers' URI schemes are not implemented just because their syntax exists.
