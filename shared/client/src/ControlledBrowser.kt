package kinuvo.client

import kinuvo.protocol.*
import kotlinx.coroutines.*

/** Typed transport for the Server-owned Browser. Input calls are never retried. */
class ControlledBrowser(private val client: KinuvoClient) {
    suspend fun tabs(owner: BrowserOwner) = bounded(30_000) { client.call(Api.BrowserTabs, owner) }
    suspend fun command(command: BrowserCommand) = bounded(if (command.action == BrowserAction.Open) 360_000L else 60_000L) {
        client.call(Api.BrowserCommand, command)
    }
    val changes get() = client.events(Api.BrowserChanged)

    private suspend fun <T> bounded(timeout: Long, block: suspend () -> T): T = try {
        withTimeout(timeout) { block() }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("Browser request timed out; inspect the tab before sending input again", e)
    }
}
