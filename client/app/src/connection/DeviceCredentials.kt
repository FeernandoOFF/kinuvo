package kinuvo.app.connection

/** Device credentials are kept separately from ordinary preferences. */
interface DeviceCredentials {
    fun read(server: String): String?
    fun write(server: String, token: String)
    fun delete(server: String)
}
