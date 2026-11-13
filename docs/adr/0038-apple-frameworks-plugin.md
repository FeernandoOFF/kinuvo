---
title: "Apple frameworks plugin"
description: "Reminders and Calendar through EventKit, in-process on the Mac desktop app and the iPhone app, without a helper tool."
---

Status: Accepted

## Decision

`plugins/apple-frameworks` (`kinuvo.apple`, instance `frameworks`) is the one place Kinuvo reaches Apple's system frameworks, with a section per framework: Reminders and Calendar (EventKit) ship first and Contacts and Location are added as further sections. It offers the `reminders` Task provider and plain Server tools, not an MCP server. Each framework is a tool group an Agent turns on (`reminders`, `calendar`), offered only where the plugin can reach Apple.

One Swift core, `AppleEventKit`, serves both platforms behind a single JSON call, the Kotlin interface `AppleSystem`: a method name and JSON parameters in, `{"result":…}` or `{"error":{"code","message"}}` out. Kotlin owns tool schemas, formatting, the Task provider and tests; Swift owns EventKit types, authorization and date handling. Adding a framework adds Swift methods and Kotlin tools, not a new boundary.

- **macOS.** The core and a `@_cdecl` wrapper build into `libKinuvoApple.dylib` (`scripts/build-mac-apple.sh`), which the desktop app bundles and the JVM loads in-process with the foreign function API, as for speech. In-process, the app holds the privacy permissions (Reminders and Calendars usage strings in its `Info.plist`, the calendars entitlement for hardened runtime), so a prompt names Kinuvo rather than a helper tool.
- **iPhone.** The same Swift file compiles into the app, which hands an `AppleSystem` to the embedded Server's plugin list.
- **Elsewhere.** A Server run with the `kinuvo` command, as a service, or on another system has no app bundle or no library. The core checks the host bundle for the usage strings first, since requesting access without them would end the process, and then every call answers `unavailable` with "Apple Reminders and Calendar need the Kinuvo desktop app on macOS or the iPhone app".

Reads are Low Risk, creating and updating Medium, deleting an event High. A call on an undetermined permission requests access; the Task provider's authorize call and each framework's status are available to clients.

Calendar events are new: CalDAV remains a Task provider (VTODO) and there is no calendar provider setting to default, so the Apple Calendar tools are the first calendar-event support and are on wherever the Server can reach Apple and the Agent enables them.

## Consequences

No separate tool or terminal authorization is needed, and the iPhone Server gains Reminders and Calendar. Permissions belong to the app, so a headless Mac Server cannot use them until the desktop-as-service work lets the app host it. The provider id `reminders` is unchanged, so saved `tasks` settings keep working. Recurring events are changed or deleted one occurrence at a time; alarms, attendees and rich recurrence editing are not exposed. Calendar access is verified by hand on a signed app, since automated tests use a fake `AppleSystem`.
