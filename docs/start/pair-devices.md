---
title: "Pair your Devices"
description: "Connect apps and browsers to a Server and revoke access when needed."
---

Keep the Server running, then ask it for a pairing code:

```sh
kinuvo pair --manage
```

Management access includes configuration, pairing and Approvals. Use `kinuvo pair` for ordinary access.

1. Scan the QR code with your phone's camera, or open the printed **App link** on a Device with Kinuvo installed.
2. For a browser, open the **Web link** at the Server's HTTPS address.
3. Check the Server address and confirm pairing. A Pairing link prefills the address and code; opening it does not pair automatically.

You can also enter the address and code in the app. A physical phone needs an address it can reach; `localhost` on the phone means the phone itself. A Device can give a remote Server its own Alias.

Codes expire after ten minutes and allow one successful pairing. Run `kinuvo pair --refresh --manage` to replace the current code. `--host` selects the address printed in the links; it does not change where the Server listens. Remote browser authentication needs HTTPS; see [browser access](../guides/browser-access.md).

## Review and revoke Devices

Use the app's **Devices** settings from a management Device, or the CLI on the Server:

```sh
kinuvo devices list
kinuvo devices revoke DEVICE
```

Replace `DEVICE` with the listed ID or an unambiguous name. Revoking ends that Device's access until it pairs again. It does not delete Agents or Sessions. Browser Device credentials expire after 90 days and can be rotated in **Settings → Connection**.

Pairing does not turn on a Device's tools. Choose what to share under **Settings → This Device's tools**; see [Computer use](../guides/computer-use.md).

Next: [set up your first Agent](first-agent.md).
