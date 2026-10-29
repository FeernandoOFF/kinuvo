package kinuvo.app.browser

import kinuvo.app.connection.ConnectedServer
import kinuvo.client.ControlledBrowser
import kinuvo.protocol.*

/** Calls stay bound to their Server lifetime. Mutations are deliberately never retried. */
class BrowserRepository(private val connection: ConnectedServer) {
    fun changes() = connection.client?.let { ControlledBrowser(it).changes } ?: kotlinx.coroutines.flow.emptyFlow()
    suspend fun tabs(owner: BrowserOwner) = connection.call { ControlledBrowser(it).tabs(owner) }
    suspend fun command(command: BrowserCommand) = connection.call { ControlledBrowser(it).command(command) }
}
