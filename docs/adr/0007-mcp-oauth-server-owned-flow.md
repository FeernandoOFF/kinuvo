---
title: "MCP OAuth: Server-owned flow, client-relayed callback"
description: "Kinuvo mCP OAuth: Server-owned flow, client-relayed callback."
---

HTTP MCP servers that answer 401 need the user to sign in through OAuth (the MCP authorization spec), and the Kotlin MCP SDK has no client support for it. The Server may run headless or on another machine while the browser opens on a Device, so the Server owns the flow: discovery (the 401's `WWW-Authenticate` → protected resource metadata → authorization server metadata), dynamic client registration (or a configured `oauth.clientId`), the PKCE verifier, the token exchange and refresh. A Device only opens the URL and hands back the URL the browser returned to (`mcp.login` → `mcp.loginComplete`); tokens never reach clients or Agents.

Redirects are fixed: `http://localhost:33418/callback` on desktop and the CLI, where the client listens; `dev.kinuvo.app:/mcp/oauth` on Android (an intent filter) and iOS (`ASWebAuthenticationSession`). Registration asks for both, so one client works from every Device, falling back to the one in use when a server refuses the app scheme. Pasting the URL the browser ended on always works, e.g. over SSH or when the port is busy.

Sign-ins follow the config's scope: one for a shared server, one per Agent for its own. They live in `~/.kinuvo/mcp-auth/<scope>/<server>.enc`, AES-GCM encrypted with a key kept in the OS keychain (whose entries, through `security -i`, can't hold tokens of 4 KB), or as owner-only plain JSON where there's no keychain. This narrows ADR 0005: self-refreshing OAuth tokens still aren't Secret references, but MCP's are kept by the Server.

Accepted cost: a fixed port can clash with another program (pasting covers it); the browser flow only, no client credentials; status "needs sign-in" is learned from a 401 and kept in memory, so a never-used server shows as not signed in until a run or Test reaches it.
