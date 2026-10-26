---
title: "Public access keeps one owner and separates Device credentials"
description: "Kinuvo public access keeps one owner and separates Device credentials."
---

Public hosting uses a loopback Server behind a trusted HTTPS proxy, exact browser-origin checks, explicit high-entropy pairing windows, and bounded unauthenticated work. We keep opaque, hashed Device credentials rather than introducing accounts or JWTs: this remains one owner's Server, while pairing explicitly grants ordinary or management access. Ordinary Devices cannot manage access, change Server configuration, or answer Approvals.

Native credentials live in OS secure storage separately from Server metadata. Hosted browsers keep regular Device credentials in Secure, HttpOnly, SameSite=Strict cookies; the Server persists only their hashes, as for native Devices. Both expire after 90 days, support rotation, and survive Server restarts. Browser pairing and rotation use HTTPS and never return credentials to JavaScript. HttpOnly protection does not require temporary access, so browsers no longer use separate twelve-hour, in-memory sessions. HTML Artifacts run in opaque-origin sandboxes even when they contain scripts. This supersedes ADR 0008's universal Device management access and perpetual pairing-code availability, preserving its link-prefill and revocation behavior.
