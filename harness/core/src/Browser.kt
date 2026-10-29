package kinuvo.harness.core

import kinuvo.protocol.*

/** Server-owned browsing, separate from paired Computers. Implementations never expose a debugging endpoint. */
interface BrowserService {
    fun onChange(listener: (BrowserOwner) -> Unit) {}
    suspend fun tabs(owner: BrowserOwner): List<BrowserTab>
    suspend fun command(command: BrowserCommand, user: Boolean = false): BrowserFrame?
}
