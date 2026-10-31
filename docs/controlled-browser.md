---
title: "Server-owned Browser"
---

JVM Servers (desktop and CLI) use managed Chromium; Android Servers use WebView
with isolated Agent profiles. Browser is separate from paired Computers. Enable
**Browser** under **Tools & MCP → Built-in tools** for the selected Agent. In a
Session, select the **Browser** globe to open tabs and Live view. Take over to
navigate, tap, scroll, type, or sign in; **Hand back** allows Agent actions again.
Management access is required on a paired Device. Android, iOS and web clients
use the same shared feature against either Server. The iOS embedded Server does
not offer Browser. See [Android Browser](reference/android-browser-host.md) for
WebView requirements and limits.

## JVM engine

Playwright Java is pinned to **1.64.0**. Its bundled Node driver automatically
installs the matching Chromium distribution on first use into
`<KINUVO_HOME>/browser/runtime/playwright-1.64.0`. No installed Chrome, global
Node, Electron, or public CDP endpoint is needed. First use needs internet
access and may take several minutes; the UI stays busy. Failed installation
reports the installer log path and can be retried. Linux needs the upstream
Chromium system libraries; Kinuvo does not elevate privileges or install OS
packages. Chromium sandboxing is enabled.

Each Agent owns a persistent profile in `<KINUVO_HOME>/browser/profiles/<Agent>`.
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
`browser_click`, `browser_type`, `browser_scroll`, `browser_wait`, `browser_close`.
Capture returns page text, visible element refs, a revision, and an image.
Click/fill require both ref and revision; changes to visible page content,
element geometry or attributes invalidate them. JVM engine operations use one
Playwright thread; Android WebView operations use Main with per-tab serialization. Capturing another semantic snapshot
also invalidates previous refs; Live view polling does not. Read/wait are Low
Risk, navigation/open/close Medium, click/fill High. Existing Approval, Trust
and Deny rules apply. Agent capture/input is refused during Takeover. By explicit user scope, Browser
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
input under the ordinary Agent policy, sandbox-enabled launch over a private pipe, and shutdown with real managed Chromium. The Server test proves
management authorization, paired transport, reconnect persistence and control.
The packaged variant starts the executable jar in an isolated temporary home,
provisions Chromium there and stops the process afterward. Rendered UI fixtures
are in `shared/ui/build/screens/browser-*.png`. The shared mobile flow is
`.maestro/flows/mobile/browser.yaml`; run it through Mockstro using only a
checkout-designated device, as documented in `.maestro/README.md`.

Managed Chromium is verified on macOS arm64. Android Agent tools, Live view,
Takeover, user tap/text input, control exclusion and reconnection recovery are
verified on a Samsung Android 16 phone. Windows, Linux, real account sign-in,
secure keyguard and long-idle/Doze operation still need verification.

Upstream references: [browser installation](https://playwright.dev/java/docs/browsers),
[persistent contexts](https://playwright.dev/java/docs/api/class-browsertype#browser-type-launch-persistent-context),
[threading](https://playwright.dev/java/docs/multithreading).
The installer calls the pinned driver's `createProcessBuilder` to run the bundled
Node CLI; this avoids nested-JAR classpath issues. Browser implementation is
independent of OpenBot source/assets.

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

The shared Screens interface requires explicit protocol version **5** on both sides of the
connection. A missing or different version is incompatible: the Server rejects
it before pairing/authentication, capabilities or cookie rotation. Browser HTTP
authentication returns 409. Updated clients check the response version before
decoding feature enums and preserve saved credentials on a protocol mismatch.
Update the Server and Device together. The unified Screens contract replaces
legacy management mutations.

The active management API is `screens.*`, through `ScreensClient`. Legacy Browser
commands and Computer input/control/watch RPCs return migration errors. Holds
belong to authenticated Devices and require the current generation to change;
input also requires a frame drawn and acknowledged on that connection. Browser
held recovery restores suspended, until explicit handback. Apps using the previous
management controls need the shared Screens UI migration.
