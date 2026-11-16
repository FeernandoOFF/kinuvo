package kinuvo.client

actual fun platformTailcatForwarder(): TailcatForwarder = object : TailcatForwarder {
    override val supported = false
    override suspend fun open(address: String): TailcatForward = error("Tailcat connections are not supported on this platform yet")
}
