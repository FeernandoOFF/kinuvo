# Public hosting

Run one Server for one owner's Devices. Ordinary access shares that owner's Agents, Sessions and Tasks; it does not create a separate user account. Unrelated users need separate instances and data directories.

## HTTPS deployment

Build a Server jar with the web app using `./package-server.sh`. Install Caddy on the same machine, point a domain's DNS at it, and expose Caddy's ports 80 and 443. Keep Kinuvo's port 8787 inaccessible from other machines; use a firewall as well as the loopback binding. Caddy's data directory must be persistent so certificate renewal survives restarts.

```sh
kinuvo serve --host 127.0.0.1 --port 8787 --public-url https://kinuvo.example.com
KINUVO_DOMAIN=kinuvo.example.com caddy run --config docs/deployment/Caddyfile
```

The example [Caddyfile](deployment/Caddyfile) terminates TLS, proxies both HTTP and WebSockets, and sets security headers. Caddy obtains and renews the certificate and redirects HTTP to HTTPS. The Server listens on loopback; public mode refuses a non-loopback binding and fails if its configured port is occupied, instead of silently choosing another port. Native clients connect using `https://kinuvo.example.com` or `wss://kinuvo.example.com/ws`. The web client must be opened at the Server's own origin. A bare public hostname defaults to WSS. Plain WS remains available for explicit private IPs, localhost, single-label LAN names, `.local` names, and Tailscale's private IP range.

To install the Server as a service:

```sh
kinuvo service install --public-url https://kinuvo.example.com
```

Caddy needs its own service configured with `KINUVO_DOMAIN`. `KINUVO_PUBLIC_URL` is an alternative to the Server flag. Only loopback proxy addresses are trusted by default. For a same-host proxy using another source address, set `KINUVO_TRUSTED_PROXIES` or pass `--trusted-proxy` as comma-separated literal IPs. The service installer preserves `KINUVO_PUBLIC_URL` and `KINUVO_TRUSTED_PROXIES`. Trust only the actual proxy: forwarded client addresses from other peers are ignored. Configure the proxy to discard untrusted incoming forwarding headers. Caddy does this by default.

A proxy running in another container or machine needs a different deployment arrangement; this recipe deliberately requires the Server and proxy to communicate through loopback on the same host. Do not publish port 8787 or point the public URL at the plaintext listener.

## Pairing and access

Pairing is closed at startup. On the Server's machine, explicitly open one ten-minute window:

```sh
kinuvo pair --manage
```

That grants management access, including Server configuration, further pairing, provider sign-in, and answering Approvals and Reflection proposals. Management Devices can also explicitly choose **Pair a management Device** in Settings → Connection. Use `kinuvo pair` for an ordinary Device that can chat, read the owner's data, use shared Device tools, manage Tasks, and revoke itself. Management Devices can revoke any paired Device. The local desktop and CLI remain management clients.

The link or QR carries a random 256-bit, single-use code. There is no short numeric fallback. Browser pairing codes stay in URL fragments, which the web app removes before pairing. Do not put them in proxy logs or share them as public links. After use, expiry, or exhaustion of the ten-attempt budget, pairing closes; only an explicit new request opens another window. Codes are not printed automatically into long-running service logs.

`kinuvo pair` advertises the configured public HTTPS origin. `--host` can override the address for private-network pairing, or accept a complete HTTPS origin.

Ordinary Devices still share the owner's data and can ask Agents to run tools under their configured Risk tolerance and Trust rules. Management access is a control on configuration and Approvals, not data isolation or an execution sandbox. Keep Agents' Risk tolerance appropriate for the Devices allowed to chat with them; run the Server as a dedicated OS user with access only to the required Workspace and services.

## Credential storage

Native Device credentials expire after 90 days. In Settings → Connection, **Rotate Device credential** replaces the credential, keeps the Device's identity and tools, renews its lifetime, and disconnects its other connections. Revocation disconnects every connection for that Device immediately; expiry is checked on incoming requests and periodically on idle connections. Re-pair after expiry.

Credentials are separate from ordinary Server names and preferences:

- macOS: login Keychain.
- Linux desktop: Secret Service through `secret-tool`; install `libsecret-tools` and have an unlocked Secret Service. There is no plaintext fallback when it is unavailable.
- Windows desktop: DPAPI encrypted to the current Windows user; only ciphertext is written to the Device credential file.
- iOS: Keychain entries restricted to this Device after first unlock.
- Android: AES-GCM encryption with a non-exportable Android Keystore key, bound to the Server URL. Ciphertext lives in the app's no-backup directory; app backup is disabled.
- Hosted web: a `Secure`, `HttpOnly`, `SameSite=Strict`, host-only cookie. JavaScript receives only a non-secret marker; persistent local storage holds Server metadata, never the Device credential.

Native credentials are written directly to the platform's secure store. A locked or unavailable store reports an error. Desktop preference writes are atomic and owner-only. Browsers use the same-origin session cookie and store no credential in local storage.

Browser sessions last twelve hours, cannot extend themselves, and end on Server restart, Device revocation, or native credential rotation. Pair again to renew them. Browser sessions require HTTPS for remote hosting; plaintext LAN browser hosting cannot retain a secure session. Native clients can still use explicit private-network WS connections. Clearing local browser metadata alone cannot revoke an unreachable Server's cookie; use a management Device to revoke it.

## Boundaries and checks

Browser origins are matched exactly against the configured public URL. Requests to establish a cookie require a trusted HTTPS proxy and the correct Origin; cookie-based WebSockets require the same Origin check. Native clients without an Origin still authenticate with their Device credential. Origin checks are a browser protection, not a substitute for authentication.

Before authentication, connections have a ten-second deadline and a 4 KiB frame limit. Failed authentication closes the socket. Unknown WebSocket credentials and new HTTP session exchanges are limited to ten attempts per source and sixty globally per minute; valid WebSocket credentials and existing browser sessions do not consume the guessing budget. Connections are capped at eight per source and sixty-four globally. Sources come from the raw peer address or the configured trusted proxy's forwarded address. HTTP authentication bodies are bounded to 4 KiB and ten seconds. After authentication, requests are limited to eight concurrent operations and six hundred messages per minute; the larger frame allowance supports existing attachments. Access-denial logs are throttled and contain no codes, tokens, cookie contents or message bodies.

Generated HTML Artifacts run in an opaque-origin iframe sandbox that permits scripts but forbids same-origin access, popups and top-level navigation. The existing resource CSP remains inside the frame. They cannot read app storage or its opener; iOS uses a non-persistent web-view store and Android disables file/content access.

Validation: authentication tests cover code consumption, expiry, rotation, management restrictions, origin/proxy handling, secure cookies, revocation, deadlines, oversized pre-authentication frames, and message limits. Storage tests cover secure storage failures and keeping credentials out of preferences; the macOS integration test writes and deletes a temporary Keychain entry. The packaged web app was paired and reloaded through this Caddy configuration over HTTPS/WSS, verifying security headers, cookie attributes, credential-free local storage, and foreign-Origin rejection. Browser verification confirms Artifact scripts work while storage access raises `SecurityError`. Android was paired and restarted with encrypted credentials, and the iOS app builds successfully. Compile shared clients and UI for JVM, Android, iOS Simulator and web when changing these flows. Windows and Linux secure stores still need runtime checks on those operating systems.
