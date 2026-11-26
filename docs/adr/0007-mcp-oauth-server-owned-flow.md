---
title: "MCP OAuth: Server-owned flow, client-relayed callback"
description: "Keep MCP OAuth tokens and flow state on the Server while Devices relay callbacks."
---

Status: Accepted

## Context

An MCP sign-in may open on a different machine from the Server.

## Decision

HTTP MCP servers that answer 401 need the user to sign in through OAuth (the MCP authorization spec), and the Kotlin MCP SDK has no client support for it. The Server may run headless or on another machine while the browser opens on a Device, so the Server owns the flow: discovery (the 401's `WWW-Authenticate` → protected resource metadata → authorization server metadata), dynamic client registration (or a configured `oauth.clientId`), the PKCE verifier, the token exchange and refresh. A Device only opens the URL and hands back the URL the browser returned to (`mcp.login` → `mcp.loginComplete`); tokens never reach clients or Agents.

Redirects are fixed: `http://127.0.0.1:33418/callback` on desktop, the CLI and iOS, where the client listens on a loopback socket (the literal IP, not `localhost`, so it can't resolve to an address nothing is bound to); `dev.kinuvo.app:/mcp/oauth` on Android (an intent filter). Registration asks for both redirects, so one client works from every Device. iOS presents the sign-in page in an in-app `ASWebAuthenticationSession` but never relies on its own custom-scheme interception for MCP: some auth servers mishandle a non-http redirect_uri (one observed concatenating it onto their own base URL instead of navigating to it), stranding the sign-in with no address bar to recover from. Pasting the URL the browser ended on always works, e.g. over SSH or when the port is busy.

Sign-ins follow the config's scope: one for a shared server, one per Agent for its own. They live in `~/.kinuvo/mcp-auth/<scope>/<server>.enc`, AES-GCM encrypted with a key kept in the OS keychain (whose entries, through `security -i`, can't hold tokens of 4 KB), or as owner-only plain JSON where there's no keychain. On iPhone they're one entry in the iOS Keychain; On Android they're one file encrypted with a non-exportable Android Keystore key. This narrows ADR 0005: self-refreshing OAuth tokens still aren't Secret references, but MCP's are kept by the Server.

## Consequences

Accepted cost: a fixed port can clash with another program (pasting covers it); the browser flow only, no client credentials; status "needs sign-in" is learned from a 401 and kept in memory, so a never-used server shows as not signed in until a run or Test reaches it.
