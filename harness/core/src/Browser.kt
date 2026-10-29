package kinuvo.harness.core

import kinuvo.protocol.*

/** Server-owned browsing, separate from paired Computers. Implementations never expose a debugging endpoint. */
interface BrowserService {
    fun screen(owner: BrowserOwner, tabId: String): ScreenSource = error("Live streaming unavailable")
    fun onChange(listener: (BrowserOwner) -> Unit) {}
    suspend fun tabs(owner: BrowserOwner): List<BrowserTab>
    suspend fun command(command: BrowserCommand, user: Boolean = false): BrowserFrame?
}
