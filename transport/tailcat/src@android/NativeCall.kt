package kinuvo.transport.tailcat

internal object TailcatJni {
    external fun abiVersion(): Int
    external fun call(method: String, params: String): String
}

internal actual fun nativeCall(): (String, String) -> String {
    System.loadLibrary("KinuvoTailcat")
    check(TailcatJni.abiVersion() == 1) { "Unsupported Tailcat ABI" }
    return TailcatJni::call
}
