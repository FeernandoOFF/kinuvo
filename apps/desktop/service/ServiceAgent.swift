import Foundation
import ServiceManagement

private let plistName = "dev.kinuvo.server.plist"

private func failure(_ error: Error) -> UnsafeMutablePointer<CChar>? {
    strdup(error.localizedDescription)
}

private func unregister(_ service: SMAppService) throws {
    let done = DispatchSemaphore(value: 0)
    var failure: Error?
    service.unregister { error in
        failure = error
        done.signal()
    }
    done.wait()
    if let failure { throw failure }
}

/// 0 not registered, 1 enabled, 2 requires approval in System Settings, 3 not found in this bundle.
@_cdecl("kinuvo_service_status")
public func serviceStatus() -> Int32 {
    Int32(SMAppService.agent(plistName: plistName).status.rawValue)
}

/// Registers the bundled launch agent, replacing a running one so it starts with the current settings. Returns an error message to free, or NULL.
@_cdecl("kinuvo_service_register")
public func registerService() -> UnsafeMutablePointer<CChar>? {
    let service = SMAppService.agent(plistName: plistName)
    do {
        switch service.status {
        case .requiresApproval: return nil
        case .enabled: try unregister(service)
        default: break
        }
        try service.register()
        return nil
    } catch {
        return failure(error)
    }
}

@_cdecl("kinuvo_service_unregister")
public func unregisterService() -> UnsafeMutablePointer<CChar>? {
    let service = SMAppService.agent(plistName: plistName)
    guard service.status != .notRegistered && service.status != .notFound else { return nil }
    do {
        try unregister(service)
        return nil
    } catch {
        return failure(error)
    }
}

@_cdecl("kinuvo_service_free")
public func freeService(_ text: UnsafeMutablePointer<CChar>) {
    free(text)
}
