import Contacts
import HealthKit
import KotlinModules

final class AppleDeviceReader: NSObject, DeviceReader {
    let kinds = ["Contacts", "Workouts", "Sleep"]
    private let health = HKHealthStore()
    private let contacts = CNContactStore()

    func authorize(kind: String, completion: @escaping (String?) -> Void) {
        if kind == "Contacts" {
            contacts.requestAccess(for: .contacts) { granted, _ in
                DispatchQueue.main.async { completion(granted ? nil : "Contacts access was denied. Enable it in iOS Settings.") }
            }
            return
        }
        guard HKHealthStore.isHealthDataAvailable() else { completion("Health data is unavailable on this Device."); return }
        let types: Set<HKObjectType>
        if kind == "Workouts" { types = [HKObjectType.workoutType()] }
        else if kind == "Sleep", let sleep = HKObjectType.categoryType(forIdentifier: .sleepAnalysis) { types = [sleep] }
        else { completion("Unsupported Device tool"); return }
        health.requestAuthorization(toShare: [], read: types) { completed, _ in
            // HealthKit does not reveal whether the user granted read access.
            DispatchQueue.main.async { completion(completed ? nil : "Health authorization could not complete.") }
        }
    }

    func read(requestJson: String, completion: @escaping (String) -> Void) {
        guard let bytes = requestJson.data(using: .utf8),
              let request = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any],
              let kind = request["kind"] as? String else {
            finish("Unavailable", message: "Invalid Device request", completion: completion); return
        }
        if kind == "Contacts" {
            DispatchQueue.global(qos: .userInitiated).async { self.readContacts(request, completion: completion) }
        } else if kind == "Workouts" || kind == "Sleep" {
            readHealth(request, kind: kind, completion: completion)
        } else { finish("Unsupported", completion: completion) }
    }

    private func readContacts(_ request: [String: Any], completion: @escaping (String) -> Void) {
        let status = CNContactStore.authorizationStatus(for: .contacts)
        guard status == .authorized || status == .limited else {
            finish("Denied", message: "Contacts access is denied. Enable it in iOS Settings.", completion: completion); return
        }
        guard let query = request["query"] as? String, !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, query.count <= 200 else {
            finish("Unavailable", message: "Supply a Contacts query", completion: completion); return
        }
        let limit = min(20, max(1, request["limit"] as? Int ?? 20))
        let keys = [CNContactGivenNameKey, CNContactFamilyNameKey, CNContactOrganizationNameKey, CNContactPhoneNumbersKey, CNContactEmailAddressesKey] as [CNKeyDescriptor]
        let fetch = CNContactFetchRequest(keysToFetch: keys)
        fetch.sortOrder = .userDefault
        let needle = query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
        let digits = query.filter(\.isNumber)
        let phoneQuery = !digits.isEmpty && query.allSatisfy { $0.isNumber || "+()- .".contains($0) }
        var matches: [[String: Any]] = []
        do {
            try contacts.enumerateContacts(with: fetch) { contact, stop in
                let name = [contact.givenName, contact.familyName].filter { !$0.isEmpty }.joined(separator: " ")
                let displayName = name.isEmpty ? contact.organizationName : name
                let phones = contact.phoneNumbers.map { $0.value.stringValue }
                let emails = contact.emailAddresses.map { String($0.value) }
                let matched = displayName.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current).contains(needle)
                    || emails.contains { $0.lowercased().contains(query.lowercased()) }
                    || (phoneQuery && phones.contains { $0.filter(\.isNumber).contains(digits) })
                if matched {
                    matches.append(["name": String(displayName.prefix(500)), "phones": Array(phones.prefix(20)).map { String($0.prefix(100)) }, "emails": Array(emails.prefix(20)).map { String($0.prefix(500)) }])
                    if matches.count > limit { stop.pointee = true }
                }
            }
            finish("Ready", data: ["source": "Apple Contacts", "access": status == .limited ? "selected contacts" : "all contacts", "contacts": Array(matches.prefix(limit)), "truncated": matches.count > limit], completion: completion)
        } catch { finish("Unavailable", message: "Contacts could not be read", completion: completion) }
    }

    private func readHealth(_ request: [String: Any], kind: String, completion: @escaping (String) -> Void) {
        guard HKHealthStore.isHealthDataAvailable() else { finish("Unsupported", message: "Health data is unavailable on this Device", completion: completion); return }
        guard let startString = request["start"] as? String, let endString = request["end"] as? String,
              let start = date(startString), let end = date(endString), end > start, end.timeIntervalSince(start) <= 32 * 86400 else {
            finish("Unavailable", message: "Choose a Health date range of at most 31 days", completion: completion); return
        }
        let type: HKSampleType = kind == "Workouts" ? HKObjectType.workoutType() : HKObjectType.categoryType(forIdentifier: .sleepAnalysis)!
        let limit = kind == "Workouts" ? min(20, max(1, request["limit"] as? Int ?? 20)) : 5000
        // Sleep dates refer to waking: include the previous night, then filter complete sessions locally.
        let queryStart = kind == "Sleep" ? start.addingTimeInterval(-36 * 3600) : start
        let predicate = HKQuery.predicateForSamples(withStart: queryStart, end: end, options: kind == "Workouts" ? .strictStartDate : [])
        let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: limit + 1, sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: kind == "Sleep")]) { _, samples, error in
            guard error == nil else { self.finish("Unavailable", message: "Health data could not be read. Check Health permissions.", completion: completion); return }
            let samples = samples ?? []
            let selected = Array(samples.prefix(limit))
            if kind == "Workouts" {
                let workouts = selected.compactMap { $0 as? HKWorkout }.map { workout -> [String: Any] in
                    var record: [String: Any] = ["id": workout.uuid.uuidString, "type": self.workoutName(workout.workoutActivityType), "activityCode": workout.workoutActivityType.rawValue,
                        "start": self.timestamp(workout.startDate), "end": self.timestamp(workout.endDate), "durationMinutes": workout.duration / 60,
                        "source": workout.sourceRevision.source.name]
                    if let distance = workout.totalDistance { record["distanceMeters"] = distance.doubleValue(for: .meter()) }
                    if let energy = workout.statistics(for: HKObjectType.quantityType(forIdentifier: .activeEnergyBurned)!)?.sumQuantity() { record["energyKilocalories"] = energy.doubleValue(for: .kilocalorie()) }
                    return record
                }
                self.finish("Ready", data: ["source": "HealthKit", "workouts": workouts, "truncated": samples.count > limit,
                    "availability": "Empty results may mean no workouts or no read access. Workouts may be incomplete until the source syncs."], completion: completion)
            } else {
                let records = selected.compactMap { $0 as? HKCategorySample }.compactMap { sample -> [String: Any]? in
                    guard let value = HKCategoryValueSleepAnalysis(rawValue: sample.value) else { return nil }
                    let stage: String
                    switch value {
                    case .inBed: stage = "in_bed"
                    case .awake: stage = "awake"
                    case .asleepCore: stage = "core"
                    case .asleepDeep: stage = "deep"
                    case .asleepREM: stage = "rem"
                    case .asleepUnspecified: stage = "asleep"
                    @unknown default: return nil
                    }
                    return ["start": self.timestamp(sample.startDate), "end": self.timestamp(sample.endDate), "stage": stage,
                        "source": sample.sourceRevision.source.name, "sourceId": sample.sourceRevision.source.bundleIdentifier]
                }
                self.finish("Ready", data: ["samples": records, "truncated": samples.count > limit], completion: completion)
            }
        }
        health.execute(query)
    }

    private func date(_ text: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        if let date = formatter.date(from: text) { return date }
        formatter.formatOptions.insert(.withFractionalSeconds)
        return formatter.date(from: text)
    }

    private func timestamp(_ date: Date) -> String { ISO8601DateFormatter().string(from: date) }

    private func workoutName(_ type: HKWorkoutActivityType) -> String {
        switch type {
        case .running: return "Running"
        case .walking: return "Walking"
        case .cycling: return "Cycling"
        case .swimming: return "Swimming"
        case .hiking: return "Hiking"
        case .yoga: return "Yoga"
        case .traditionalStrengthTraining: return "Strength training"
        case .functionalStrengthTraining: return "Functional strength training"
        case .highIntensityIntervalTraining: return "HIIT"
        case .rowing: return "Rowing"
        case .pilates: return "Pilates"
        case .elliptical: return "Elliptical"
        case .dance: return "Dance"
        case .mixedCardio: return "Mixed cardio"
        case .other: return "Other"
        default: return "Health activity \(type.rawValue)"
        }
    }

    private func finish(_ status: String, data: [String: Any] = [:], message: String? = nil, completion: @escaping (String) -> Void) {
        var result: [String: Any] = ["status": status, "data": data]
        if let message { result["message"] = message }
        let bytes = try? JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
        let json = bytes.flatMap { String(data: $0, encoding: .utf8) } ?? "{\"status\":\"Unavailable\",\"message\":\"Device result could not be encoded\"}"
        DispatchQueue.main.async { completion(json) }
    }
}
