import Foundation
import KinuvoTailcat
import KotlinModules

/// Hands the linked Tailcat library (Go, statically linked) to the shared Kotlin code, which does all the JSON and lifecycle work.
enum AppleTailcat {
    static func install() {
        TailcatIosKt.installTailcat { method, params in
            method.withCString { methodPointer in
                params.withCString { paramsPointer in
                    guard let result = kinuvo_tailcat_call(UnsafeMutablePointer(mutating: methodPointer), UnsafeMutablePointer(mutating: paramsPointer)) else {
                        return #"{"error":"Tailcat operation failed"}"#
                    }
                    defer { kinuvo_tailcat_free(result) }
                    return String(cString: result)
                }
            }
        }
    }
}
