package kinuvo.client

/** The browser's WebSocket has its own limit and Ktor can't change it, so this keeps Ktor's default, which it leaves alone. */
internal actual val MAX_FRAME_SIZE: Long = Int.MAX_VALUE.toLong()
