# Server-owned Browser

The JVM Server (desktop and CLI) offers a controlled Chromium browser, separate
from paired Computers. Enable **Browser** in an Agent's tools. In a Session,
select the **Browser** globe in the header to open tabs and Live view. Take over
to navigate, tap, scroll, type, or sign in; **Hand back** allows Agent actions
again. Management access is required on a paired Device. Android, iOS and web
clients use the same shared feature against a JVM Server; embedded mobile
Servers do not offer Browser.

Playwright Java is pinned to **1.64.0**. Its bundled Node driver automatically
installs the matching Chromium distribution on first use into
`<KINUVO_HOME>/browser/runtime/playwright-1.64.0`. No installed Chrome, global
Node, Electron, or public CDP endpoint is needed. First use needs internet
access and may take several minutes; the UI stays busy. Failed installation
reports the installer log path and can be retried. Linux needs the upstream
Chromium system libraries; Kinuvo does not elevate privileges or install OS
packages. Chromium sandboxing is enabled.

Each Agent owns a persistent profile in `<KINUVO_HOME>/browser/profiles/<Agent>`.
Cookies and local storage survive Server restarts. Tabs belong to an Agent and
Session and survive Device disconnects while the Server runs. Open tabs and
pending actions are not restored after Server shutdown/crash; no uncertain
input is replayed. Agents share neither profiles nor tab access. Concurrent
Sessions of the same Agent share that Agent's logins. Changing Agent settings
releases its open browser context, retaining its profile on disk.

Tools: `browser_tabs`, `browser_open`, `browser_capture`, `browser_navigate`,
`browser_click`, `browser_type`, `browser_scroll`, `browser_wait`, `browser_close`.
Capture returns page text, visible element refs, a revision, and an image.
Click/fill require both ref and revision; changes to visible page content,
element geometry or attributes invalidate them. All operations run on a single
Playwright thread, serializing tab input. Capturing another semantic snapshot
also invalidates previous refs; Live view polling does not. Read/wait are Low
Risk, navigation/open/close Medium, click/fill High. Existing Approval, Trust
and Deny rules apply. Agent capture/input is refused during Takeover. Password
fields cannot be filled by Agents. Use user Takeover for passwords, codes,
CAPTCHAs, payments and security dialogs. No password-manager value injection is
implemented.

HTTP and HTTPS private-network/self-hosted sites are intentional. Explicit
navigation rejects embedded credentials, files and custom protocols; document
requests, link clicks, form submissions and popups are also filtered. TLS
verification stays enabled. File transfer/downloads and native dialogs are not
supported. Cross-origin iframe elements are not yet included in semantic refs;
use the screenshot and user Takeover for those controls.

Live view polls JPEG frames about every 750 ms while its ViewModel is open;
sidebar thumbnails refresh at most every three seconds. This is an initial
interactive view rather than a video stream. Protocol calls are `browser.tabs`
and `browser.command`, with `browser.changed` notifications. These methods use
the existing paired/authenticated management channel and never return a CDP
address. URL query strings and fragments are omitted from tab metadata. Input is never automatically retried after disconnect/error.

Verification commands (in a pinned checkout):

```sh
mise run kotlin -- test -m browser -p jvm
mise run kotlin -- test -m jvm -p jvm --include-classes='*BrowserServerTest'
mise run kotlin -- test -m app -p jvm --include-classes='*BrowserStateTest'
mise run kotlin -- test -m ui -p jvm --include-classes='*BrowserScreenTest'
mise run kotlin -- package -m cli -f executable-jar
mise run exec -- env KINUVO_BROWSER_PACKAGED_JAR="$PWD/build/tasks/_cli_executableJarJvm/cli-jvm-executable.jar" mise run kotlin -- test -m jvm -p jvm --include-classes='*BrowserServerTest'
```

The runtime test uses a localhost fixture and proves persistent cookies/local
storage, cross-Agent isolation, ownership, stale refs, Takeover, password-field
refusal, sandbox-enabled launch over a private pipe, and shutdown with real managed Chromium. The Server test proves
management authorization, paired transport, reconnect persistence and control.
The packaged variant starts the executable jar in an isolated temporary home,
provisions Chromium there and stops the process afterward. Rendered UI fixtures
are in `shared/ui/build/screens/browser-*.png`. The shared mobile flow is
`.maestro/flows/mobile/browser.yaml`; run it through Mockstro using only a
checkout-designated device, as documented in `.maestro/README.md`.

Locally verified on macOS arm64. Windows, Linux, real account sign-in and mobile
device runtime interaction need platform/device verification.

Upstream references: [browser installation](https://playwright.dev/java/docs/browsers),
[persistent contexts](https://playwright.dev/java/docs/api/class-browsertype#browser-type-launch-persistent-context),
[threading](https://playwright.dev/java/docs/multithreading).
The installer calls the pinned driver's `createProcessBuilder` to run the bundled
Node CLI; this avoids nested-JAR classpath issues. Browser implementation is
independent of OpenBot source/assets.
