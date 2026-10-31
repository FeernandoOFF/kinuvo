package kinuvo.harness.core

import kinuvo.protocol.*

/** Server-owned browsing, separate from paired Computers. Implementations never expose a debugging endpoint. */
interface BrowserService {
    suspend fun close() {}
    fun openingGate(owner: BrowserOwner): ScreenGate = error("Screen opening unavailable")
    suspend fun gate(owner: BrowserOwner, tabId: String): ScreenGate = error("Screen control unavailable")
    suspend fun controlChanged(owner: BrowserOwner, tabId: String) {}
    suspend fun userCommand(command: BrowserCommand): BrowserFrame? = error("Screen input unavailable")
    fun screen(owner: BrowserOwner, tabId: String): ScreenSource = error("Live streaming unavailable")
    fun authorize(check: suspend (BrowserOwner) -> Unit) {}
    suspend fun release(agentId: String) {}
    suspend fun deleteAgent(agentId: String) { release(agentId) }
    fun onChange(listener: (BrowserOwner) -> Unit) {}
    suspend fun owners(): List<BrowserOwner> = emptyList()
    suspend fun tabs(owner: BrowserOwner): List<BrowserTab>
    suspend fun command(command: BrowserCommand, user: Boolean = false): BrowserFrame?
}
