# Browser access

The Server includes the web app. Open it at the Server's own HTTPS address, then pair that browser. Native apps can also use explicit private HTTP/WS addresses; remote browser sign-in requires HTTPS even over Tailscale.

## Private HTTPS with Tailscale

Install Tailscale on the Server and the Devices using it, join them to your tailnet, and enable MagicDNS and HTTPS certificates in [Tailscale's DNS settings](https://login.tailscale.com/admin/dns).

For a foreground Server:

```sh
kinuvo serve --tailscale
```

Or install/update its login service:

```sh
kinuvo service install --tailscale
kinuvo pair --manage
```

`--tailscale` discovers this machine's full Tailscale DNS name, configures [Tailscale Serve](https://tailscale.com/docs/reference/tailscale-cli/serve), and sets Kinuvo's accepted HTTPS origin. Serve manages the certificate and proxies HTTPS/WebSocket connections to Kinuvo on `127.0.0.1`. The HTTPS address and pairing links remain private to your tailnet. Pairing and Approvals still belong to Kinuvo; Tailscale connectivity does not grant access by itself.

Open the **Web link** printed by `kinuvo pair` in the browser you want to pair. Use `kinuvo pair --manage` for Server configuration and Approvals, or `kinuvo pair` for ordinary access.

The default Kinuvo port is 8787 and the default HTTPS port is 443. To keep a different backend port, and to share the Server desktop as a Computer:

```sh
kinuvo service install --port 8788 --computer --tailscale
```

If another Serve route uses port 443, select a free HTTPS port:

```sh
kinuvo serve --tailscale --tailscale-port 8443
```

The resulting address includes `:8443`. Existing conflicting routes are refused rather than overwritten. An existing route to this exact Kinuvo port is reused. Funnel routes are refused because Funnel exposes a service publicly.

The Server binds to `127.0.0.1`; omit `--host` or use that address. `--tailscale` and `--public-url` are alternative setup modes. Once installed, the service retains its Tailscale flags and re-establishes HTTPS after restarts. Native apps must also use the new HTTPS address instead of the old directly exposed IP address; pair at that address if prompted.

Serve routes persist after Kinuvo stops or its service is uninstalled. Inspect them with `tailscale serve status`. To disable only the default-port HTTPS route, run `tailscale serve --https=443 off`; substitute your chosen port when necessary. This leaves routes on other ports intact.

If setup fails, check `tailscale status`, HTTPS certificate settings and permission to run `tailscale serve`. On macOS, the application-bundled CLI and Homebrew location are also detected. Certificate hostnames are recorded in the public Certificate Transparency ledger; the service itself stays private. See [Tailscale's HTTPS documentation](https://tailscale.com/docs/how-to/set-up-https-certificates).

For a public domain and an independently managed HTTPS proxy, see [public hosting](public-hosting.md).

## Why remote HTTP cannot sign in

Tailscale encrypts its network traffic, but browsers still treat `http://100.x.x.x` as HTTP. Kinuvo authenticates browsers using a `Secure`, `HttpOnly`, `SameSite=Strict` cookie, keeping credentials out of JavaScript and local storage. The browser needs HTTPS for that cookie outside supported localhost exceptions, and the Server rejects remote HTTP browser authentication. A private window does not change these requirements. See [browser cookie rules](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Cookies).

## Temporary access and session lifetimes

- A pairing link works for **one successful pairing**, within ten minutes. Opening it only prefills the form; confirming pairing consumes the code.
- Browser Device credentials last **90 days**, survive Server restarts, and can be renewed with **Rotate Device credential** in Settings → Connection. Reloading does not extend expiry. Revocation or rotation invalidates the old credential; pair again after expiry.
- A normal browser can retain the session cookie after closing a tab or restarting. For temporary sign-in, pair in a **private/incognito window** and close all private windows when finished. Server names and UI preferences are stored separately from authentication.
- Temporary sign-in does not make conversations temporary: Agents, Sessions and the paired Device record remain on the Server. Revoke the Device from a management Device to end access immediately.

There is no token-in-memory HTTP mode. For a Server without `--tailscale` or a configured `--public-url`, an SSH tunnel can provide local browser access:

```sh
ssh -N -L 127.0.0.1:18787:127.0.0.1:8787 macmini
```

Visit `http://localhost:18787` while the tunnel stays open. Run `kinuvo pair` on the Server and enter its code there; do not paste a remote-IP pairing link over the localhost address. Substitute the Server's actual port for the final `8787`. This relies on the browser's secure-cookie localhost exception: Chrome and Firefox support it; use HTTPS with Safari. A Server configured with an HTTPS origin requires that origin, so the tunnel does not bypass its origin checks.

## Troubleshooting

- **HTTP loads the page but cannot pair:** use `--tailscale`, another HTTPS proxy, or the native app. Loading the web assets is separate from authenticated access.
- **HTTPS opens but authentication is refused:** the proxy and Kinuvo must agree on the exact origin, including a non-default port. `--tailscale` configures both; for a manual proxy, configure `--public-url`, trusted proxy addresses and `X-Forwarded-Proto: https`.
- **The browser asks to pair again:** the cookie expired or was discarded, or the Device was revoked or its credential rotated elsewhere. This does not mean the Server lost your Agents or Sessions. Browser sessions created before persistent Device credentials were introduced require one new pairing.
- **iOS reports "TLS sessions are not supported on Native platform":** update or rebuild the iOS app. Older builds selected a networking engine without native TLS support; the iOS client now uses Apple's networking engine for HTTPS and secure WebSockets.
