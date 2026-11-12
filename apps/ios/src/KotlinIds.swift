import KotlinModules

extension AgentInfo {
    var key: String { NativeIds.shared.agent(info: self) }
}

extension RouteChat {
    var agentKey: String? { NativeIds.shared.agent(route: self) }
    var sessionKey: String? { NativeIds.shared.session(route: self) }
}

extension ActiveCall {
    var agentKey: String { NativeIds.shared.agent(call: self) }
    var sessionKey: String { NativeIds.shared.session(call: self) }
}
