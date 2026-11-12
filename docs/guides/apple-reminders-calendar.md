---
title: "Apple Reminders and Calendar"
description: "Let Agents read and change your Reminders and Calendar events from a Mac or iPhone Server."
---

A Server running in the Kinuvo desktop app on a Mac, or in the iPhone app, can reach your Reminders and Calendar through Apple's own frameworks. Nothing is installed besides Kinuvo, and your data stays on that device until an Agent's tool result goes into a Session.

A Server started with the `kinuvo` command, as a service, or on another system cannot reach them; their tools explain this when called. Use a CalDAV, Todoist or local Task provider there instead.

## Allow access

1. Open **Settings → Tasks**, choose **Apple Reminders** and tap **Request access**, or let the first tool call ask. macOS or iOS asks whether Kinuvo may use Reminders; Calendar asks the same way the first time an Agent uses it.
2. If you declined, enable Kinuvo under **System Settings → Privacy & Security → Reminders** or **Calendars** on a Mac, or **Settings → Kinuvo** on an iPhone, then try again.

## Turn on an Agent's tools

Under **Tools & MCP → Built-in tools**, turn on **Reminders** and **Calendar** for each Agent that may use them; they appear only on a Server that can reach Apple's frameworks.

| Tool | What it does | Risk |
| --- | --- | --- |
| `reminders_lists`, `reminders_list`, `reminders_get` | Read lists and reminders | Low |
| `reminders_create`, `reminders_update` | Add, change, complete or reopen a reminder | Medium |
| `calendar_list`, `calendar_events` | Read calendars; list or search events in a date range | Low |
| `calendar_event_create`, `calendar_event_update` | Add or change an event | Medium |
| `calendar_event_delete` | Delete an event | High |

Approvals follow the Agent's Risk tolerance as for any tool. Times are in the Server's time zone. Changing or deleting one occurrence of a repeating event leaves the others alone.

## Use Reminders as the Task app

Picking **Apple Reminders** in **Settings → Tasks** makes your lists the Board's lists, as for any [task provider](../concepts/routines-and-tasks.md). A Task keeps a due date, not a time; reminders with a time keep theirs in the Reminders app.
