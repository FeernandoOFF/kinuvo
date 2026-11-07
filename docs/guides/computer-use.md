---
title: "Computer use"
description: "Share a Computer with an Agent and take control through Live view."
---

Agents with the `computer` tool group see and control a Computer: a paired Device that allowed it under
Settings → This Device's tools, or the CLI's desktop enabled with `--computer`.

Enable **Computer use** in the Agent's tools, then ask it to list available Computers. Name the Computer you want when more than one is available. The Agent can inspect apps, capture the screen and use supported controls such as tapping, typing, scrolling and keyboard shortcuts. macOS also supports window and menu actions; Android uses long press for context menus.

Screenshots can include private information and are sent to the Agent's model provider. Check what is visible before enabling Computer use.

## Risk

App/window listings, verification and zoom are Low. Clipboard reads and force-quitting are High and **always need an
Approval**, including with `full` Risk tolerance or saved Trust rules; "Always allow" isn't offered for them.
Capturing is Medium. Other actions ask for every app until its Approval's "Always allow in <app>" saves the Trust rule
`computer_act <app>` (the package or bundle id, or the name `open_app` used, e.g. `google_maps`), much like a shell
command prefix; a rule for the whole `computer_act` tool doesn't cover any app. Acting is High, so only a `full` Risk
tolerance skips these Approvals. Waiting and the back, home, recents and notifications keys are Low. Typing into a
password field, acting in system settings, permission, install and app-store screens, and acting before any capture
are High and can never be trusted. `set_value` follows the same password checks as typing. Each run keeps its own
captures, so element numbers and Approvals refer to what that Agent saw; the request names the approved app, and the Computer refuses the action if another app is in front or the
field turned out to be a password field. Agents are told to treat on-screen text as data and to ask the user to take over for
passwords, one-time codes, payments, CAPTCHAs and permission dialogs.

Device Computers answer while the app is in the background, unlike Device reads.

## Android

Settings → This Device's tools → Computer opens Android's Accessibility settings; turn on "Kinuvo computer use". On
Android 13 and later, a sideloaded app first needs "Allow restricted settings" from its App info menu. The phone can answer while Kinuvo is in the background. Android 11 and later provide screenshots; Android 8–10 provide element-based control without screenshots. Kinuvo refuses Agent taps and typing in its own app and in permission or install screens. Going home, back or opening an app still works from there.

## Live view and Takeover

Computers in the sidebar (for Devices with management access) refreshes each Computer’s screen while you watch, with who controls it:
Idle, Agent or the user. Management Devices only. Take over starts a Takeover: Agents' captures and actions wait until
Hand back, and the user's taps, swipes, keys and typing go to the Computer; two fingers zoom and pan the screen (for Android, including screens Agents may
not touch).

`request_takeover` asks the user to take over for a step only they should do. It shows a form in the Session (with a
Notification when nobody watches) and returns when the user submits it, skips it, or hands the Computer back.

While an Agent acts on an Android phone, an overlay says "Kinuvo is using this phone" with Take over; tapping it pauses
Agents on that phone until Hand back. The Server checks every two seconds whether the user handed it back.

## Desktops

The CLI can expose its desktop without the desktop app:

```sh
cua-driver permissions grant
kinuvo serve --computer
```

For a Server installed as a user service, run `kinuvo service install --computer` instead. The option is saved in the
service command; re-run installation without it to turn sharing off. The service installer preserves the installing
shell's graphical-session environment on Linux. Enable **Computer use** in the Agent's Tools settings, then ask it to
list its Computers. The CLI registers **Server desktop** as a local Device; captures, Approvals, Live view and Takeover
work as they do in the desktop app. Keep an accessible desktop session running under the same user, with
cua-driver installed and its OS permissions granted. This shares an existing desktop; it doesn't create a virtual display.

On Android, Computer captures and actions wake the display and keep it awake for 30 seconds after the last request.
Live view and Takeover wake renewals extend that lease. Waking the display doesn't unlock the phone.

On macOS, starting Takeover wakes the display and keeps the display and Computer awake while Live view or the user's
input continues. Hand back releases the wake assertion immediately; if the client disconnects, it expires within
15 seconds. Waking the display doesn't unlock the session.

The desktop app offers its own screen like a phone does: Settings → This Device's tools → Computer, for whichever
Server it's connected to, including the one it embeds. It needs [cua-driver](https://github.com/trycua/cua) (on PATH,
in `~/.local/bin` or `/Applications/CuaDriver.app`) with Accessibility and Screen Recording (`cua-driver permissions
grant`). Locked or sleeping displays may show black screenshots. Keep the desktop session available and unlocked when you expect the Agent to work.

During Takeover, use the keyboard and key controls in Live view to type or send shortcuts. Desktop Computers offer modifier keys such as Command, Option, Control and Shift; Android offers Back, Home and Recents. Hand back explicitly when you are ready for the Agent to continue. A disconnected Takeover stays suspended until you recover it or hand back.
