import Foundation

private final class Bridge {
    let lock = NSLock()
    var events = [String]()
    lazy var speech = Speech { [weak self] event, id, text in
        guard let self else { return }
        let data = try! JSONSerialization.data(withJSONObject: ["event": event, "id": id, "text": text])
        self.lock.lock()
        self.events.append(String(decoding: data, as: UTF8.self))
        self.lock.unlock()
    }
}

@_cdecl("kinuvo_speech_create")
public func createSpeech() -> UnsafeMutableRawPointer {
    Unmanaged.passRetained(Bridge()).toOpaque()
}

@_cdecl("kinuvo_speech_send")
public func sendSpeech(_ handle: UnsafeMutableRawPointer, _ json: UnsafePointer<CChar>) {
    let bridge = Unmanaged<Bridge>.fromOpaque(handle).takeUnretainedValue()
    guard let data = String(cString: json).data(using: .utf8),
          let command = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
    // AppKit's existing main run loop owns all audio and authorization operations.
    DispatchQueue.main.async { bridge.speech.handle(command) }
}

@_cdecl("kinuvo_speech_poll")
public func pollSpeech(_ handle: UnsafeMutableRawPointer) -> UnsafeMutablePointer<CChar>? {
    let bridge = Unmanaged<Bridge>.fromOpaque(handle).takeUnretainedValue()
    bridge.lock.lock()
    defer { bridge.lock.unlock() }
    guard !bridge.events.isEmpty else { return nil }
    return strdup(bridge.events.removeFirst())
}

@_cdecl("kinuvo_speech_free")
public func freeSpeechEvent(_ event: UnsafeMutableRawPointer) { free(event) }

@_cdecl("kinuvo_speech_close")
public func closeSpeech(_ handle: UnsafeMutableRawPointer) {
    let bridge = Unmanaged<Bridge>.fromOpaque(handle).takeRetainedValue()
    let stop = {
        bridge.speech.handle(["op": "stop"])
        bridge.speech.handle(["op": "stopSpeaking"])
    }
    // JVM shutdown may already be waiting on AppKit; never wait for its main queue here.
    // The queued closure retains the bridge until native cleanup can run.
    DispatchQueue.main.async(execute: stop)
}
