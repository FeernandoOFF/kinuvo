package kinuvo.ui

import kinuvo.app.connection.*

internal class BrowserCredentials : DeviceCredentials {
    private val unpaired = mutableSetOf<String>()
    override fun read(server: String) = if (server in unpaired) null else "browser-session"
    override fun write(server: String, token: String) {
        unpaired.remove(server)
    }
    override fun delete(server: String) { unpaired += server }
}
