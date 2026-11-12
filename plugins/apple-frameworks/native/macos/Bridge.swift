import Foundation

private let eventKit = AppleEventKit()

@_cdecl("kinuvo_apple_call")
public func kinuvo_apple_call(_ method: UnsafePointer<CChar>, _ params: UnsafePointer<CChar>) -> UnsafeMutablePointer<CChar>? {
    strdup(eventKit.call(method: String(cString: method), params: String(cString: params)))
}

@_cdecl("kinuvo_apple_free")
public func kinuvo_apple_free(_ pointer: UnsafeMutablePointer<CChar>?) {
    free(pointer)
}
