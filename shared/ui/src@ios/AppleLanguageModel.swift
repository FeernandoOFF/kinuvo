import Foundation
import FoundationModels
import KotlinModules

private struct BridgeTool: Decodable {
    let name: String
    let description: String
    let parameters: [BridgeParameter]
}

private struct BridgeParameter: Decodable {
    let name: String
    let description: String
    let type: String
    let required: Bool
    let values: [String]?
    let items: String?
}

/** Each turn is a reply or one tool call, told apart by its `tool` value. */
@available(iOS 26, *)
private func turnSchema(_ tools: [BridgeTool]) throws -> GenerationSchema {
    let reply = DynamicGenerationSchema(name: "reply", description: "Answer the user", properties: [
        .init(name: "tool", schema: DynamicGenerationSchema(name: "reply_tool", anyOf: ["reply"])),
        .init(name: "reply", description: "The answer to the user", schema: DynamicGenerationSchema(type: String.self)),
    ])
    let calls = tools.map { tool in
        DynamicGenerationSchema(name: "call_\(tool.name)", description: tool.description, properties:
            [.init(name: "tool", schema: DynamicGenerationSchema(name: "\(tool.name)_tool", anyOf: [tool.name]))] +
            tool.parameters.map { .init(name: $0.name, description: $0.description, schema: schema(for: $0, in: tool.name), isOptional: !$0.required) })
    }
    let root = DynamicGenerationSchema(name: "turn", description: "A reply to the user, or one tool call", anyOf: [reply] + calls)
    return try GenerationSchema(root: root, dependencies: [])
}

@available(iOS 26, *)
private func schema(for parameter: BridgeParameter, in tool: String) -> DynamicGenerationSchema {
    if let values = parameter.values { return DynamicGenerationSchema(name: "\(tool)_\(parameter.name)", anyOf: values) }
    if parameter.type == "array" { return DynamicGenerationSchema(arrayOf: primitive(parameter.items ?? "string")) }
    return primitive(parameter.type)
}

@available(iOS 26, *)
private func primitive(_ type: String) -> DynamicGenerationSchema {
    switch type {
    case "integer": DynamicGenerationSchema(type: Int.self)
    case "number": DynamicGenerationSchema(type: Double.self)
    case "boolean": DynamicGenerationSchema(type: Bool.self)
    default: DynamicGenerationSchema(type: String.self)
    }
}

final class AppleSystemLanguageModel: NSObject, AppleLanguageModel {
    private var tasks: [String: Task<Void, Never>] = [:]

    // iOS 27 SDK APIs (context size, Private Cloud Compute) build with Xcode 27's Swift. Private Cloud Compute also needs
    // the com.apple.developer.private-cloud-compute entitlement, which Apple grants on request: without it the first
    // request traps, so it's only compiled in when the build sets KINUVO_PCC (see apps/ios/README.md).
    func contextSize(model: String, completion: @escaping (KotlinInt) -> Void) {
        if model == "private-cloud" {
            #if compiler(>=6.4) && KINUVO_PCC
            if #available(iOS 27, *) {
                Task { @MainActor in completion(KotlinInt(int: Int32((try? await PrivateCloudComputeLanguageModel().contextSize) ?? 0))) }
                return
            }
            #endif
            completion(KotlinInt(int: 0))
            return
        }
        #if compiler(>=6.4)
        if #available(iOS 26, *) { completion(KotlinInt(int: Int32(SystemLanguageModel.default.contextSize))); return }
        #endif
        completion(KotlinInt(int: 4096))
    }

    func unavailableCode(model: String) -> String? {
        if model == "private-cloud" {
            #if compiler(>=6.4) && KINUVO_PCC
            if #available(iOS 27, *) {
                let cloud = PrivateCloudComputeLanguageModel()
                if cloud.quotaUsage.isLimitReached { return "quota_exceeded" }
                return cloud.isAvailable ? nil : "private_cloud_unavailable"
            }
            #endif
            return "private_cloud_not_included"
        }
        guard #available(iOS 26, *) else { return "os_version_too_old" }
        #if targetEnvironment(simulator)
        return "simulator_unsupported"
        #else
        switch SystemLanguageModel.default.availability {
        case .available: return nil
        case .unavailable(.deviceNotEligible): return "device_not_eligible"
        case .unavailable(.appleIntelligenceNotEnabled): return "apple_intelligence_not_enabled"
        case .unavailable(.modelNotReady): return "model_not_ready"
        case .unavailable: return "model_unavailable"
        }
        #endif
    }

    func unavailableReason(model: String) -> String? {
        switch unavailableCode(model: model) {
        case nil: return nil
        case "quota_exceeded": return "You've reached today's Private Cloud Compute limit. Use the on-device model, or raise the limit with iCloud+."
        case "private_cloud_unavailable": return "Private Cloud Compute is unavailable. It needs Apple Intelligence, an internet connection and a supported region."
        case "private_cloud_not_included": return "This build doesn't include Private Cloud Compute."
        case "os_version_too_old": return "On-device chat requires iOS 26 or later. You can connect to a remote Server instead."
        case "simulator_unsupported": return "On-device chat needs a physical Apple Intelligence-capable device. Connect to a remote Server to chat in the simulator."
        case "device_not_eligible": return "This device does not support Apple Intelligence. Connect to a remote Server to chat."
        case "apple_intelligence_not_enabled": return "Enable Apple Intelligence in Settings to use on-device chat."
        case "model_not_ready": return "Apple's on-device model is still downloading. Try again when Apple Intelligence is ready."
        default: return "Apple's on-device model is unavailable. Try again later or connect to a remote Server."
        }
    }

    func generate(requestId: String, model: String, instructions: String, prompt: String, toolsJson: String,
                  onText: @escaping (String) -> Void, completion: @escaping (String?, String?) -> Void) {
        if let reason = unavailableReason(model: model) { completion(nil, reason); return }
        guard #available(iOS 26, *) else { return }
        tasks[requestId] = Task { @MainActor in
            defer { tasks.removeValue(forKey: requestId) }
            do {
                let tools = try JSONDecoder().decode([BridgeTool].self, from: Data(toolsJson.utf8))
                let toolInstructions = tools.isEmpty ? "" : """

You can request one tool per turn: set tool to its name and fill its arguments; the harness runs it and shows the
result next turn. Set tool to reply to answer the user. Never claim an action succeeded before its tool result.
""" + (tools.contains { $0.name == "memory" } ? """
When the user asks you to remember a fact or preference, request memory with action=add, target=user and a concise
content. After "Tool memory result: OK", reply with a brief confirmation. Read Current personal context for recall.
""" : "")
                let session = Self.session(model, instructions: instructions + toolInstructions)
                let options = GenerationOptions(sampling: .greedy, maximumResponseTokens: model == "private-cloud" ? 2048 : 512)
                if !tools.isEmpty {
                    var last: GeneratedContent?
                    for try await snapshot in session.streamResponse(to: prompt, schema: try turnSchema(tools), options: options) {
                        try Task.checkCancellation()
                        last = snapshot.content
                        if (try? snapshot.content.value(String.self, forProperty: "tool")) == "reply",
                           let reply = try? snapshot.content.value(String.self, forProperty: "reply") { onText(reply) }
                    }
                    try Task.checkCancellation()
                    guard let last else { completion(nil, "Apple's model returned no response."); return }
                    completion(last.jsonString, nil)
                } else {
                    var text = ""
                    for try await snapshot in session.streamResponse(to: prompt, options: options) {
                        try Task.checkCancellation()
                        text = snapshot.content
                        onText(text)
                    }
                    try Task.checkCancellation()
                    let data = try JSONSerialization.data(withJSONObject: ["tool": "reply", "reply": text])
                    completion(String(decoding: data, as: UTF8.self), nil)
                }
            } catch is CancellationError {
                completion(nil, "Stopped")
            } catch LanguageModelSession.GenerationError.exceededContextWindowSize {
                completion(nil, "This conversation is too long for this Apple model. Start a new Session; your Memory and User Notes are kept.")
            } catch LanguageModelSession.GenerationError.assetsUnavailable {
                completion(nil, "Apple's on-device model is not ready. Enable Apple Intelligence in Settings and wait for its download to finish.")
            } catch LanguageModelSession.GenerationError.refusal {
                completion(nil, "Apple's model declined this request. Try rephrasing it.")
            } catch LanguageModelSession.GenerationError.guardrailViolation {
                completion(nil, "Apple's model declined this request. Try rephrasing it.")
            } catch LanguageModelSession.GenerationError.unsupportedLanguageOrLocale {
                completion(nil, "Apple's model does not support this language or region.")
            } catch {
                completion(nil, "Apple's model could not finish: \(error.localizedDescription)")
            }
        }
    }

    @available(iOS 26, *)
    private static func session(_ model: String, instructions: String) -> LanguageModelSession {
        #if compiler(>=6.4) && KINUVO_PCC
        if model == "private-cloud", #available(iOS 27, *) { return LanguageModelSession(model: PrivateCloudComputeLanguageModel(), instructions: instructions) }
        #endif
        return LanguageModelSession(instructions: instructions)
    }

    func cancel(requestId: String) {
        DispatchQueue.main.async { self.tasks[requestId]?.cancel() }
    }
}
