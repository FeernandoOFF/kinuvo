package kinuvo.ui

import kinuvo.app.connection.*

internal class IosCredentials : DeviceCredentials {
    private val keychain get() = checkNotNull(deviceKeychain) { "iOS Keychain is unavailable" }
    override fun read(server: String) = keychain.read("dev.kinuvo.devices", server)
    override fun write(server: String, token: String) {
        check(keychain.write("dev.kinuvo.devices", server, token) && read(server) == token) { "Couldn't save the Device credential to the iOS Keychain" }
    }
    override fun delete(server: String) { keychain.delete("dev.kinuvo.devices", server) }
}
