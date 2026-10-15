package kinuvo.ui

import kinuvo.app.connection.*

import kinuvo.harness.core.Keychain
import kinuvo.harness.core.iosKeychain

/** Generic passwords in the iOS Keychain, implemented in Swift. */
interface NativeKeychain {
    fun read(service: String, account: String): String?
    fun write(service: String, account: String, value: String): Boolean
    fun delete(service: String, account: String)
}

internal var deviceKeychain: NativeKeychain? = null

fun installKeychain(native: NativeKeychain) {
    deviceKeychain = native
    iosKeychain = { service, account ->
        object : Keychain {
            override val name = "iOS Keychain"
            override suspend fun read() = native.read(service, account)
            override suspend fun write(token: String) = check(native.write(service, account, token)) { "Couldn't save to the iOS Keychain" }
            override suspend fun delete() = native.delete(service, account)
        }
    }
}
