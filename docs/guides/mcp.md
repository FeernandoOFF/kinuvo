---
title: "Connect MCP servers"
description: "Add stdio and HTTP tools, share them across Agents and sign in with OAuth."
---

MCP servers add tools to an Agent. Configure them in **Settings → MCP** or with the CLI. A shared entry is available to every Agent; an Agent entry with the same name replaces it for that Agent.

## Add and test

For a stdio server, the command runs on the Server machine. Replace the example path with your installed MCP server:

```sh
kinuvo -a default mcp add local-tools --command /path/to/mcp-server -- --stdio
kinuvo -a default mcp test local-tools
```

For a Streamable HTTP server:

```sh
kinuvo -a default mcp add remote-tools --url https://mcp.example.com/mcp
kinuvo -a default mcp test remote-tools
kinuvo -a default mcp list
```

Use `--shared` on add to share it. Use repeated `--include TOOL` to expose only selected tools, or `--exclude TOOL` to hide tools. `--env KEY=VALUE` sets stdio environment values; `--header KEY=VALUE` sets HTTP headers. Values support `${VAR}` and [Secret references](../reference/secrets.md); command and argument strings refuse references.

By default, tools' read-only hints inform Risk. `--approval ask` or `--approval auto` overrides that classification; the Server still applies its Approval policy and Deny rules. See [Approvals](../concepts/approvals.md).

## OAuth sign-in

If an HTTP server needs OAuth:

```sh
kinuvo -a default mcp login remote-tools
```

The Server owns discovery, registration, PKCE, token exchange and refresh. The Device opens the sign-in page and relays the returned URL. Desktop and CLI use `http://localhost:33418/callback`; mobile apps use the app callback. If the listener cannot be used, paste the URL the browser ended on. Use `--no-browser` to print the URL instead of opening it.

For a provider that requires a pre-registered OAuth client, add `--client-id`, optional `--client-secret`, and repeated `--oauth-scope` values. Sign-in follows the entry's scope; use `--shared` for a shared entry. Tokens stay on the Server, separate from Secret references.

```sh
kinuvo -a default mcp logout remote-tools
kinuvo -a default mcp remove remote-tools
```

## File format

Shared config is `<Server home>/mcp.json`; Agent config is `agents/<id>/mcp.json`. Both use `mcpServers`:

```json
{
  "mcpServers": {
    "remote-tools": {
      "url": "https://mcp.example.com/mcp",
      "headers": { "Authorization": "Bearer ${pass://Kinuvo/Tools/token}" }
    }
  }
}
```

For many tools, the Agent uses the Tool catalog to find, describe and call them on demand. Scripts using `run_code` can call tools under the same Approvals.

Sources: [McpCommands](https://github.com/FeernandoOFF/kinuvo/blob/main/apps/cli/src/McpCommands.kt), [ADR 0007](../adr/0007-mcp-oauth-server-owned-flow.md), [ADR 0012](../adr/0012-central-tools-and-tool-catalog.md).
