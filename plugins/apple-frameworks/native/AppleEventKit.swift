import EventKit
import Foundation

private struct AppleFailure: Error {
    let code: String
    let message: String
    init(_ code: String, _ message: String) { self.code = code; self.message = message }
}

private final class Box<T>: @unchecked Sendable {
    var value: T
    init(_ value: T) { self.value = value }
}

private struct Params {
    let raw: [String: Any]
    func string(_ key: String) -> String? { (raw[key] as? String).flatMap { $0.isEmpty ? nil : $0 } }
    func text(_ key: String) -> String? { raw[key] as? String }
    func bool(_ key: String) -> Bool? { raw[key] as? Bool }
    func int(_ key: String) -> Int? { raw[key] as? Int }
    func require(_ key: String) throws -> String {
        guard let value = string(key) else { throw AppleFailure("invalid", "Missing '\(key)'") }
        return value
    }
}

/// EventKit behind one JSON call (`{"result":…}` or `{"error":{"code","message"}}`), shared by the macOS library and the iOS app.
final class AppleEventKit: @unchecked Sendable {
    static let unavailable = "Apple Reminders and Calendar need the Kinuvo desktop app on macOS or the iPhone app"

    private let store = EKEventStore()
    private let lock = NSLock()
    private let calendar = Calendar.current

    func call(method: String, params: String) -> String {
        lock.lock()
        defer { lock.unlock() }
        do {
            let input = try JSONSerialization.jsonObject(with: Data(params.utf8)) as? [String: Any] ?? [:]
            return try encode(["result": try run(method, Params(raw: input))])
        } catch let failure as AppleFailure {
            return (try? encode(["error": ["code": failure.code, "message": failure.message]])) ?? "{}"
        } catch {
            return (try? encode(["error": ["code": "failed", "message": error.localizedDescription]])) ?? "{}"
        }
    }

    private func encode(_ value: Any) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: value), as: UTF8.self)
    }

    private func run(_ method: String, _ p: Params) throws -> Any {
        switch method {
        case "status": return ["reminders": status(.reminder), "calendar": status(.event)]
        case "authorize": return try authorize(try p.require("framework") == "calendar" ? .event : .reminder)
        case "reminders.lists": try access(.reminder); return store.calendars(for: .reminder).map(calendarJSON)
        case "reminders.list": try access(.reminder); return try reminders(p)
        case "reminders.get": try access(.reminder); return reminderJSON(try reminder(p.require("id")))
        case "reminders.create": try access(.reminder); return try createReminder(p)
        case "reminders.update": try access(.reminder); return try updateReminder(p)
        case "calendar.list": try access(.event); return store.calendars(for: .event).map(calendarJSON)
        case "calendar.events": try access(.event); return try events(p)
        case "calendar.create": try access(.event); return try createEvent(p)
        case "calendar.update": try access(.event); return try updateEvent(p)
        case "calendar.delete": try access(.event); return try deleteEvent(p)
        default: throw AppleFailure("invalid", "Unknown method '\(method)'")
        }
    }

    // MARK: Authorization

    private func usageKey(_ type: EKEntityType) -> String {
        type == .reminder ? "NSRemindersFullAccessUsageDescription" : "NSCalendarsFullAccessUsageDescription"
    }

    private func status(_ type: EKEntityType) -> String {
        if Bundle.main.object(forInfoDictionaryKey: usageKey(type)) == nil { return "unavailable" }
        switch EKEventStore.authorizationStatus(for: type) {
        case .notDetermined: return "notDetermined"
        case .restricted: return "restricted"
        case .denied: return "denied"
        case .fullAccess: return "fullAccess"
        case .writeOnly: return "writeOnly"
        default: return "denied"
        }
    }

    private func deniedMessage(_ type: EKEntityType) -> String {
        let name = type == .reminder ? "Reminders" : "Calendars"
        #if os(macOS)
        let place = "System Settings → Privacy & Security → \(name)"
        #else
        let place = "Settings → Kinuvo → \(name)"
        #endif
        return "\(name) access is \(status(type)). Enable Kinuvo in \(place), then try again."
    }

    private func authorize(_ type: EKEntityType) throws -> Any {
        var state = status(type)
        if state == "unavailable" { throw AppleFailure("unavailable", Self.unavailable) }
        if state == "notDetermined" {
            try requestAccess(type)
            state = status(type)
        }
        return state == "fullAccess" ? ["status": state] : ["status": state, "message": deniedMessage(type)]
    }

    private func requestAccess(_ type: EKEntityType) throws {
        let done = DispatchSemaphore(value: 0)
        if type == .reminder { store.requestFullAccessToReminders { _, _ in done.signal() } } else { store.requestFullAccessToEvents { _, _ in done.signal() } }
        guard done.wait(timeout: .now() + 120) == .success else { throw AppleFailure("failed", "Access request timed out") }
    }

    private func access(_ type: EKEntityType) throws {
        if status(type) == "notDetermined" { _ = try authorize(type) }
        switch status(type) {
        case "fullAccess": return
        case "unavailable": throw AppleFailure("unavailable", Self.unavailable)
        default: throw AppleFailure("denied", deniedMessage(type))
        }
    }

    // MARK: Dates

    private static func formatter(_ format: String) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = format
        return formatter
    }

    private static let inputFormats = ["yyyy-MM-dd'T'HH:mm:ssXXXXX", "yyyy-MM-dd'T'HH:mmXXXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm"].map(formatter)
    private static let dayFormat = formatter("yyyy-MM-dd")
    private static let timeFormat = formatter("yyyy-MM-dd'T'HH:mm")

    private func parse(_ text: String) throws -> (date: Date, dateOnly: Bool) {
        let value = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "T")
        if let date = Self.dayFormat.date(from: value) { return (date, true) }
        if let date = Self.inputFormats.lazy.compactMap({ $0.date(from: value) }).first { return (date, false) }
        throw AppleFailure("invalid", "'\(text)' isn't a date; write 2026-11-12 or 2026-11-12T14:30")
    }

    private func endOfDay(_ date: Date) -> Date {
        calendar.date(byAdding: .second, value: -1, to: calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: date))!)!
    }

    private func dayText(_ date: Date) -> String { Self.dayFormat.string(from: date) }
    private func timeText(_ date: Date) -> String { Self.timeFormat.string(from: date) }

    // MARK: Lists and calendars

    private func calendarJSON(_ calendar: EKCalendar) -> [String: Any] {
        ["id": calendar.calendarIdentifier, "title": calendar.title, "writable": calendar.allowsContentModifications, "source": calendar.source?.title ?? ""]
    }

    private func calendar(_ key: String?, _ type: EKEntityType) throws -> EKCalendar? {
        guard let key else { return nil }
        let all = store.calendars(for: type)
        if let match = all.first(where: { $0.calendarIdentifier == key }) ?? all.first(where: { $0.title.caseInsensitiveCompare(key) == .orderedSame }) { return match }
        throw AppleFailure("notFound", "No \(type == .reminder ? "list" : "calendar") with the id or name '\(key)'")
    }

    // MARK: Reminders

    private func reminderJSON(_ reminder: EKReminder) -> [String: Any] {
        var json: [String: Any] = [
            "id": reminder.calendarItemIdentifier, "list_id": reminder.calendar.calendarIdentifier, "list": reminder.calendar.title,
            "title": reminder.title ?? "", "notes": reminder.notes ?? "", "done": reminder.isCompleted,
        ]
        if let due = reminder.dueDateComponents, let year = due.year, let month = due.month, let day = due.day {
            var text = String(format: "%04d-%02d-%02d", year, month, day)
            if let hour = due.hour { text += String(format: "T%02d:%02d", hour, due.minute ?? 0) }
            json["due"] = text
        }
        return json
    }

    private func reminder(_ id: String) throws -> EKReminder {
        guard let reminder = store.calendarItem(withIdentifier: id) as? EKReminder else { throw AppleFailure("notFound", "No reminder with the id '\(id)'") }
        return reminder
    }

    private func reminders(_ p: Params) throws -> Any {
        let lists = try calendar(p.string("list"), .reminder).map { [$0] }
        let predicate = p.bool("include_done") == true
            ? store.predicateForReminders(in: lists)
            : store.predicateForIncompleteReminders(withDueDateStarting: nil, ending: nil, calendars: lists)
        let box = Box<[EKReminder]>([])
        let done = DispatchSemaphore(value: 0)
        store.fetchReminders(matching: predicate) { box.value = $0 ?? []; done.signal() }
        guard done.wait(timeout: .now() + 60) == .success else { throw AppleFailure("failed", "Reminders didn't answer in time") }
        let sorted = box.value.map(reminderJSON).sorted {
            (($0["due"] as? String) ?? "9999", $0["title"] as? String ?? "") < (($1["due"] as? String) ?? "9999", $1["title"] as? String ?? "")
        }
        return Array(sorted.prefix(p.int("limit") ?? 50))
    }

    private func setDue(_ reminder: EKReminder, _ text: String) throws {
        let (date, dateOnly) = try parse(text)
        reminder.dueDateComponents = calendar.dateComponents(dateOnly ? [.year, .month, .day] : [.year, .month, .day, .hour, .minute], from: date)
        reminder.alarms?.forEach(reminder.removeAlarm)
        if !dateOnly { reminder.addAlarm(EKAlarm(absoluteDate: date)) }
    }

    private func createReminder(_ p: Params) throws -> Any {
        let reminder = EKReminder(eventStore: store)
        reminder.title = try p.require("title")
        reminder.notes = p.string("notes")
        guard let list = try calendar(p.string("list"), .reminder) ?? store.defaultCalendarForNewReminders() else {
            throw AppleFailure("failed", "There's no Reminders list to add to")
        }
        reminder.calendar = list
        if let due = p.string("due") { try setDue(reminder, due) }
        try store.save(reminder, commit: true)
        return reminderJSON(reminder)
    }

    private func updateReminder(_ p: Params) throws -> Any {
        let reminder = try reminder(p.require("id"))
        if let title = p.string("title") { reminder.title = title }
        if let notes = p.text("notes") { reminder.notes = notes }
        if p.bool("clear_due") == true || p.string("due") == "none" {
            reminder.dueDateComponents = nil
            reminder.alarms?.forEach(reminder.removeAlarm)
        } else if let due = p.string("due") { try setDue(reminder, due) }
        if let done = p.bool("done") { reminder.isCompleted = done }
        try store.save(reminder, commit: true)
        return reminderJSON(reminder)
    }

    // MARK: Events

    private func eventJSON(_ event: EKEvent) -> [String: Any] {
        let allDay = event.isAllDay
        return [
            "id": event.eventIdentifier ?? "", "calendar_id": event.calendar.calendarIdentifier, "calendar": event.calendar.title,
            "title": event.title ?? "", "start": allDay ? dayText(event.startDate) : timeText(event.startDate),
            "end": allDay ? dayText(event.endDate) : timeText(event.endDate), "all_day": allDay,
            "location": event.location ?? "", "notes": event.notes ?? "", "recurring": event.hasRecurrenceRules,
        ]
    }

    private func event(_ p: Params) throws -> EKEvent {
        let id = try p.require("id")
        if let occurrence = p.string("occurrence") {
            let at = try parse(occurrence).date
            let window = store.predicateForEvents(withStart: at.addingTimeInterval(-86_400), end: at.addingTimeInterval(86_400), calendars: nil)
            guard let match = store.events(matching: window).first(where: { $0.eventIdentifier == id && abs($0.startDate.timeIntervalSince(at)) < 60 }) else {
                throw AppleFailure("notFound", "No occurrence of event '\(id)' starts at \(occurrence)")
            }
            return match
        }
        guard let event = store.event(withIdentifier: id) else { throw AppleFailure("notFound", "No event with the id '\(id)'") }
        return event
    }

    private func events(_ p: Params) throws -> Any {
        let from = try p.string("start").map { try parse($0).date } ?? Date()
        let start = p.string("start").map { _ in from } ?? calendar.startOfDay(for: from)
        let end = try p.string("end").map { text -> Date in
            let (date, dateOnly) = try parse(text)
            return dateOnly ? endOfDay(date) : date
        } ?? calendar.date(byAdding: .day, value: 7, to: start)!
        guard end >= start else { throw AppleFailure("invalid", "'end' is before 'start'") }
        let lists = try calendar(p.string("calendar"), .event).map { [$0] }
        let query = p.string("query")?.lowercased()
        let found = store.events(matching: store.predicateForEvents(withStart: start, end: end, calendars: lists))
            .filter { event in
                guard let query else { return true }
                return [event.title, event.location, event.notes].contains { $0?.lowercased().contains(query) == true }
            }
            .sorted { $0.startDate < $1.startDate }
        return found.prefix(p.int("limit") ?? 50).map(eventJSON)
    }

    private func schedule(_ event: EKEvent, _ p: Params) throws {
        let start = try p.string("start").map(parse)
        let end = try p.string("end").map(parse)
        let allDay = p.bool("all_day") ?? start?.dateOnly ?? event.isAllDay
        let oldStart: Date? = event.startDate
        let oldEnd: Date? = event.endDate
        guard let from = start?.date ?? oldStart else { throw AppleFailure("invalid", "Missing 'start'") }
        let length = oldStart.flatMap { s in oldEnd.map { $0.timeIntervalSince(s) } } ?? 3600
        let begin = allDay ? calendar.startOfDay(for: from) : from
        let finish = end.map { allDay ? endOfDay($0.date) : $0.date } ?? (allDay ? endOfDay(begin) : begin.addingTimeInterval(length))
        guard finish >= begin else { throw AppleFailure("invalid", "'end' is before 'start'") }
        event.isAllDay = allDay
        event.startDate = begin
        event.endDate = finish
    }

    private func createEvent(_ p: Params) throws -> Any {
        let event = EKEvent(eventStore: store)
        event.title = try p.require("title")
        _ = try p.require("start")
        try schedule(event, p)
        event.location = p.string("location")
        event.notes = p.string("notes")
        guard let target = try calendar(p.string("calendar"), .event) ?? store.defaultCalendarForNewEvents else {
            throw AppleFailure("failed", "There's no calendar to add to")
        }
        event.calendar = target
        try store.save(event, span: .thisEvent, commit: true)
        return eventJSON(event)
    }

    private func updateEvent(_ p: Params) throws -> Any {
        let event = try event(p)
        if let title = p.string("title") { event.title = title }
        if let location = p.text("location") { event.location = location }
        if let notes = p.text("notes") { event.notes = notes }
        if let target = try calendar(p.string("calendar"), .event) { event.calendar = target }
        if p.string("start") != nil || p.string("end") != nil || p.bool("all_day") != nil { try schedule(event, p) }
        try store.save(event, span: .thisEvent, commit: true)
        return eventJSON(event)
    }

    private func deleteEvent(_ p: Params) throws -> Any {
        let event = try event(p)
        let id = event.eventIdentifier ?? ""
        try store.remove(event, span: .thisEvent, commit: true)
        return ["deleted": id]
    }
}
