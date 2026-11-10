package kinuvo.server.plugin

/** Where a token Kinuvo signs in with is kept: the OS keychain, or a file only the owner can read. */
interface Keychain {
    val name: String
    suspend fun read(): String?
    suspend fun write(token: String)
    suspend fun delete()

    companion object
}
