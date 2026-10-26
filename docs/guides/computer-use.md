---
title: "Computer use"
description: "Kinuvo computer use."
---

Agents with the `computer` tool group see and control a Computer: a paired Device that allowed it under
Settings → This Device's tools, or the CLI's desktop enabled with `--computer`.

- `computer_list` lists Computers, availability and supported action names. The Agent is offered only actions and read tools supported by an available Computer. Unsupported actions on the selected Computer fail clearly.
  Several available Computers need an explicit `computer` id.
- `computer_capture` returns the foreground app and numbered elements (role, label, state, centre point) and attaches a
  JPEG screenshot. Coordinates are in screenshot pixels.
- `computer_act` taps, double-taps, long-presses, types, swipes, scrolls, presses keys (`back`, `home`, `recents`,
  `notifications`, `enter`, or combinations on desktops), opens apps or waits, then returns the new screen. Element
  numbers refer to the latest capture; the Computer rejects stale ones.
- `computer_apps` lists installed apps with names, bundle/package ids, running/frontmost flags and sensitive marking.
  On Android it lists launcher apps; background running status is unknown (`null`).
- `computer_windows` (Mac) lists window ids, apps, titles and frames in desktop points.

Both Mac and Android add `set_value` (`element`, `text`, replacing the value), `clipboard_write` (`text`, including empty),
`zoom` (`x`, `y`, `width`, `height` in the latest screenshot's pixels), and `verify_state` (`text`, `role`, `front_app`).
Android `set_value` targets text fields.
Zoom crops the saved screenshot on the Server without changing action coordinates or element numbers. Verification fetches
fresh elements without a screenshot and returns `matched=true/false`: text is a case-insensitive substring, role and front
app match exactly ignoring case; text and role must match the same element, and all supplied predicates must hold.
Capture again before using element numbers after verification.

Mac also adds `right_click` (element or coordinates), `invoke_menu` (`path`, an array of exact labels in the front app),
`clipboard_read`, `set_window_frame` (`window` from `computer_windows`, `x`, `y`, `width`, `height` in desktop points),
and `kill_app` (`app`, name or bundle id). Android uses `long_press` for context menus and doesn't offer clipboard reads,
menu paths, window management or force-quitting. Desktop capabilities also reflect the installed cua-driver tools.

Each Computer serves one action at a time, for every Agent. Only the latest three screenshots are sent to the model
again; older results keep their text. Screenshots are stored like other attachments.

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
Android 13 and later, a sideloaded app first needs "Allow restricted settings" from its App info menu. The phone then
answers `computer.act` from its Server connection, also while Kinuvo is in the background, through the Accessibility
service: elements come from the accessibility tree (the active window first), screenshots from `takeScreenshot` on
Android 11+ (scaled to 1024px, JPEG). Android 8–10 control the phone by elements and coordinates without screenshots. Kinuvo never taps or types in its own app or in permission and install screens; going home, back or
opening an app still works from there.

## Live view and Takeover

Computers in the sidebar (for Devices with management access) shows each Computer's screen about three times a second and after every action, with who controls it:
Idle, Agent or the user. Management Devices only. Take over starts a Takeover: Agents' captures and actions wait until
Hand back, and the user's taps, swipes, keys and typing go to the Computer; two fingers zoom and pan the screen (for Android, including screens Agents may
not touch). Frames carry no elements and don't replace the Agent's capture.

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
use the same protocol as the desktop app. Keep an accessible desktop session running under the same user, with
cua-driver installed and its OS permissions granted. This shares an existing desktop; it doesn't create a virtual display.

On macOS, starting Takeover wakes the display and keeps the display and Computer awake while Live view or the user's
input continues. Hand back releases the wake assertion immediately; if the client disconnects, it expires within
15 seconds. Waking the display doesn't unlock the session.

The desktop app offers its own screen like a phone does: Settings → This Device's tools → Computer, for whichever
Server it's connected to, including the one it embeds. It needs [cua-driver](https://github.com/trycua/cua) (on PATH,
in `~/.local/bin` or `/Applications/CuaDriver.app`) with Accessibility and Screen Recording (`cua-driver permissions
grant`). The app runs `cua-driver mcp`, which forwards to cua-driver's daemon:

- Captures are the main display (`get_desktop_state`, sent as JPEG) plus the elements of the active
  app's window (`get_window_state`) when its accessibility tree resolves. A locked or sleeping display gives black
  screenshots and no elements.
- Element actions use cua-driver's element tokens, which work without bringing windows forward; coordinates are
  screenshot pixels, which cua-driver maps back to the screen in the app's own cua-driver session. Long press is a right
  click; keys and combinations such as `cmd+c` go to the frontmost app; `open_app` launches by name or bundle id and
  brings it forward. Typing needs an element.
- Captures are 1600px on their long side (JPEG quality 0.8), so small desktop text stays readable to Agents and in Live view.
- Live view shows modifier keys to hold (⌘ ⌥ ⌃ ⇧ on macOS, Ctrl Alt Shift Win elsewhere) and common keys, sent together
  as one combination, instead of the phone's Back, Home and Apps.

`KINUVO_CUA_SMOKE=1 ./kotlin test -m cua -p jvm` captures this machine's screen through the installed driver.
