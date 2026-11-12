---
title: "Server-owned Browser"
description: "Contributor guide to Browser engines, lifecycle, control and verification."
---

JVM Servers (desktop and CLI) use Chrome/Chromium through kdriver; Android Servers use WebView
with isolated Agent profiles. Browser is separate from paired Computers. Enable
**Browser** under **Tools & MCP → Built-in tools** for the selected Agent. In a
Session, select the **Browser** globe to open tabs and Live view. Take over to
navigate, tap, scroll, type, or sign in; **Hand back** allows Agent actions again.
Management access is required on a paired Device. Android, iOS and web clients
use the same shared feature against either Server. The iOS embedded Server does
not offer Browser. See [Android Browser](reference/android-browser-host.md) for
WebView requirements and limits.

## JVM engine

The JVM backend uses **kdriver 0.6.1** and an installed Chrome or Chromium
executable. Chrome is discovered on macOS, Linux and Windows; set
`KINUVO_BROWSER_EXECUTABLE` to an absolute executable path to override discovery.
Kinuvo does not download a browser or need a Node driver. If Chrome is missing,
install it on the Server or configure the executable path and retry.

Chrome runs headless by default, without a browser window. **Browser options → Show browser window**
switches the Agent's Chrome process after a confirmation warning that unsaved form
data and live page state will be lost. Chrome restarts in the selected mode; tabs
reopen and profile cookies/logins are kept. The preference is saved per Agent on
the Server; showing a browser window is off by default. Old streams and control generations are invalidated;
Takeover must be resumed before sending input again.

Turning **Show browser window** off runs Chrome headless, without normal browser
windows or its macOS Dock presence. Neither restart mode restores unsaved forms, JavaScript state or navigation
history.

Kinuvo launches Chrome with sandboxing and site isolation enabled,
then connects kdriver over a loopback-only debugging port. It does not use
kdriver's default launch flags or expose the debugging endpoint to Devices.
Linux needs the browser's upstream system libraries; Kinuvo does not elevate
privileges or install OS packages.

Each Agent owns a persistent profile in
`<KINUVO_HOME>/browser/kdriver-profiles/<Agent>`. Saved tab metadata is stored in
`<KINUVO_HOME>/browser/kdriver-tabs.json`. Existing profiles from the previous JVM
engine are left on disk and are not imported; sign in again in the new profile.
Cookies and local storage survive Server restarts. Tabs belong to an Agent, shared
across its Sessions, and survive Device disconnects while the Server runs. The JVM engine
restores saved tab metadata after restart, suspending any held Takeover; it does
not replay pending actions. Android tabs currently last only while its Server
process runs. No uncertain input is replayed. Agents share neither profiles nor tab
access. Sessions of the same Agent share tabs and logins. Closing or changing a tab
affects every Session; Takeover applies across all of them. Deleting a Session leaves
its Agent’s tabs open. Changing Agent settings
releases its open browser context, retaining its profile on disk.

## Tools and control

Tools: `browser_tabs`, `browser_open`, `browser_capture`, `browser_navigate`,
`browser_back`, `browser_forward`, `browser_click`, `browser_type`,
`browser_scroll`, `browser_wait`, `browser_close`.
Back and forward move one entry through the tab's native history and capture the
result; at a history boundary they leave the page unchanged. The address bar has
matching controls during user Takeover.
Capture returns page text, visible element refs, a revision, and an image.
Click/fill require both ref and revision; changes to visible page content,
element geometry or attributes invalidate them. JVM commands are serialized through the Browser runtime; Android WebView operations use Main with per-tab serialization. Capturing another semantic snapshot
also invalidates previous refs; Live view polling does not. Listing tabs, opening,
capturing, navigating, back/forward, scrolling and waiting are Low Risk and run
without Approval at the default Risk tolerance. Close is Medium Risk; click/fill
are High Risk. Strict tolerance still asks for every tool. Existing Approval, Trust
and Deny rules apply. Agent capture/input is refused during Takeover. Browser
adds no password, OTP or payment-field refusals or per-site Trust rules. Existing
Agent Risk tolerance, Approval, Trust and Deny rules govern these actions too.
Typed values must not be logged. No password-manager value injection is implemented.

HTTP and HTTPS private-network/self-hosted sites are intentional. Explicit
navigation rejects embedded credentials, files and custom protocols. The JVM
engine also filters document requests, links, forms and popups; Android filters
WebView navigation and disables file/content access and separate popup windows. TLS
verification stays enabled. File transfer/downloads and native dialogs are not
supported. Cross-origin iframe elements are not yet included in semantic refs;
use the screenshot and user Takeover for those controls.

## Mobile controls

Browser and Computer Live view share a compact mobile layout: a themed stage
outlined by control state, a header with **Hand back** and **Screen options**, and a
bottom dock. While watching, the dock offers the Screen picker and **Take over**.
During Takeover it holds **Keyboard**, **Keys**, **Trackpad mode**, **Clipboard**,
**Switch apps** (Computers only) and the Screen picker. Screen taps and drags never
open the mobile keyboard automatically; use the keyboard button. Selecting a tab
thumbnail selects that tab and dismisses the picker. Browser tabs and the address
bar are in **Tabs & address**; **Computers** opens the Computer picker. Takeover
and handback remain explicit actions. The mobile Browser starts with a desktop viewport;
**Fit to phone** adapts its viewport to the available panel. Computer screens
always preserve their source aspect ratio.

**Trackpad mode** moves a local cursor with one finger. Tap anywhere on the canvas
to click at the cursor, use two fingers to scroll, and pinch to zoom. **Recenter
pointer** brings the cursor back into the visible viewport. When zoomed, the
viewport pans with cursor movement, clamped at the screen edges. Motion alone sends no
remote input and does not trigger hover or drag remote objects. Switch back to
touch mode for direct tapping. Cursor state is local to the selected Screen and
control generation; displayed-frame checks still govern every remote action.

**Clipboard → Paste** reads the Device clipboard only when explicitly selected. It
sends text to the focused remote field while the same Screen, subscription and
Takeover are still active. It does not synchronize clipboards automatically. Desktop
Computers also offer **Copy on screen** and **Paste on screen**, which send the
Computer's own copy and paste shortcuts. **Switch apps** sends Recents on Android,
Command+Tab on macOS and Alt+Tab on Windows and Linux.

On desktop, Browser options are in the toolbar’s three-dot menu. On mobile,
**Show browser window** is part of **Screen options**. The globe button in chat
toggles the Browser sidebar; the expanded Browser has no separate sidebar button.

The mobile **Keys** button opens a key tray above the dock independently of the text keyboard.
Android exposes Back, Home, Recents and system controls. Desktop screens expose
Escape, Tab, arrows and editing keys. Select modifiers and then a tray key to send
a shortcut; modifiers clear after sending or losing control. Only keys advertised
by the selected Screen are shown.

## Typing and viewport

Tabs held by this Device have a green outline and hand icon, including unselected
tabs. A suspended hold uses amber. Other Devices' holds are not highlighted as yours.
During Takeover, the JVM Browser viewport in a dock or **Fit to phone** mode adapts to the available panel size,
including the space left when a mobile keyboard is open. Other viewers of the
tab see the same viewport. Computer screens keep their source aspect ratio.

On desktop, click the page field to focus it and type directly with a hardware keyboard.
Use the keyboard button on touch devices to open the system keyboard and a compact
row of icon keys. Text is sent as it is committed, without a Send step; IME
composition waits until commit. Pasting inserts text from the client's clipboard.
Editing keys and modifier shortcuts are forwarded while remote input has focus;
the address bar and other app fields retain their own keyboard input. Clipboard
copy/cut on the remote page does not synchronize the Server clipboard to the Device.

Input is queued in order with a bounded backlog. Changing tab, control, subscription
or viewport geometry discards incompatible queued input. Unknown or partial input
outcomes stop the queue and are not replayed.

## Live view

Live view uses the shared `screens.*` interface. JVM Browser streams CDP frames;
Android provides capture refresh at up to three frames per second while watched.
A tab shares one bounded frame producer across viewers. Frames carry geometry,
subscription epoch and sequence; user input names an acknowledged displayed frame
and the current control generation. Disconnect suspends Takeover until explicit
recovery or handback. The paired/authenticated management channel never returns
a debugging address. URL query strings and fragments are omitted from tab
metadata. Input is never automatically retried after disconnect/error.

Verification commands:

```sh
mise run kotlin -- test -m browser -p jvm
mise run kotlin -- test -m jvm -p jvm --include-classes='*BrowserServerTest'
mise run kotlin -- test -m app -p jvm --include-classes='*LiveViewModelTest'
mise run kotlin -- test -m ui -p jvm --include-classes='*LiveViewScreenTest'
mise run kotlin -- package -m cli -f executable-jar
mise run exec -- env KINUVO_BROWSER_PACKAGED_JAR="$PWD/build/tasks/_cli_executableJarJvm/cli-jvm-executable.jar" mise run kotlin -- test -m jvm -p jvm --include-classes='*BrowserServerTest'
```

The runtime test uses a localhost fixture and proves persistent cookies/local
storage, cross-Agent isolation, ownership, stale refs, Takeover, password-field
input under the ordinary Agent policy, sandbox-enabled launch over a loopback debugging connection, and graceful Chrome shutdown. The Server test proves
management authorization, paired transport, reconnect persistence and control.
The packaged variant starts the executable jar in an isolated temporary home,
launches installed Chrome with an isolated profile and stops the process afterward. Rendered UI fixtures
are in `client/ui/build/screens/browser-*.png`. The shared mobile flow is
`.maestro/flows/mobile/browser.yaml`; run it through Mockstro using only a
checkout-designated device, as documented in `.maestro/README.md`.

Verify platform behavior separately: real sign-ins, secure keyguard, long-idle and Doze behavior need runtime checks on the target system.

Upstream references: [kdriver](https://github.com/cdpdriver/kdriver) and
[Chrome DevTools Protocol](https://chromedevtools.github.io/devtools-protocol/).
The Browser implementation is independent of OpenBot source/assets.

## Ubuntu 24.04 sandbox startup

If AppArmor blocks Chromium's unprivileged user namespaces, an operator can run
`sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0` for the current
boot. To persist it, the operator can place
`kernel.apparmor_restrict_unprivileged_userns=0` in
`/etc/sysctl.d/60-apparmor-namespace.conf`; remove the file and reboot to undo it.
This loosens a host-wide restriction. A path-specific AppArmor profile granting
`userns,` is a narrower alternative. These are administrator-managed choices;
Kinuvo never changes host sysctls or disables Chromium's sandbox.

## Protocol compatibility

The shared Screens interface requires an explicit protocol version on both sides of
the connection; the Server and Device connect on the newest version both speak
([ADR 0037](adr/0037-protocol-version-negotiation.md)). Without one, the Server
rejects the connection before pairing/authentication, capabilities or cookie
rotation; browser HTTP authentication returns 409. Clients check the response
version before decoding and preserve saved credentials on a protocol mismatch.

The active management API is `screens.*`, through `ScreensClient`. Legacy Browser
commands and Computer input/control/watch RPCs return migration errors. Holds
belong to authenticated Devices and require the current generation to change;
input also requires a frame drawn and acknowledged on that connection. Browser
held recovery restores suspended, until explicit handback. Apps using the previous
management controls need the shared Screens UI migration.

### Initial frames

Each JVM Browser stream starts with a viewport capture, including restored tabs
that are in the background. Active streams retain one latest sample for viewers
joining later; that sample is discarded when the last viewer leaves. Opening a
full Live view brings its tab forward so Chrome continues delivering screencast
updates. Thumbnail subscriptions do not activate tabs.
