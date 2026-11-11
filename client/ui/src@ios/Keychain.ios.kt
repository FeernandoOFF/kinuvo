package kinuvo.ui

/** Generic passwords in the iOS Keychain, implemented in Swift. */
interface NativeKeychain {
    fun read(service: String, account: String): String?
    fun write(service: String, account: String, value: String): Boolean
    fun delete(service: String, account: String)
}

internal var deviceKeychain: NativeKeychain? = null

fun installKeychain(native: NativeKeychain) { deviceKeychain = native }
