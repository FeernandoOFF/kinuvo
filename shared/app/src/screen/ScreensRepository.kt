package kinuvo.app.screen

import kinuvo.app.connection.ConnectedServer
import kinuvo.client.*
import kinuvo.protocol.*
import kotlinx.coroutines.flow.*

interface ScreensRepository {
    val connected: Flow<Boolean>
    val frames: Flow<ScreenFrame>
    val status: Flow<ScreenStreamStatus>
    val changes: Flow<BrowserOwner?>
    suspend fun device(): String
    suspend fun list(owner: BrowserOwner?): List<ScreenInfo>
    suspend fun watch(ref: ScreenRef, thumbnail: Boolean = false): ScreenSubscription
    suspend fun unwatch(id: String)
    suspend fun ack(frame: ScreenFrameAck)
    suspend fun control(request: ScreenControlRequest): ScreenControl
    suspend fun input(request: ScreenInputRequest): ScreenInputResult
    suspend fun open(request: ScreenOpen): ScreenInfo
    suspend fun command(request: ScreenCommand)
}

internal class ConnectedScreens(private val connection: ConnectedServer) : ScreensRepository {
    private val client = requireNotNull(connection.client)
    private val screens = ScreensClient(client)
    override val connected = client.state.map { it is ConnectionState.Connected }.distinctUntilChanged()
    override val frames = screens.frames
    override val status = screens.status
    override val changes = screens.changes
    override suspend fun device() = connection.call { requireNotNull(it.lastConnect?.deviceId) { "Reconnect to identify this Device" } }
    override suspend fun list(owner: BrowserOwner?) = connection.run { screens.list(owner) }
    override suspend fun watch(ref: ScreenRef, thumbnail: Boolean) = connection.run { screens.watch(ref, thumbnail) }
    override suspend fun unwatch(id: String) = connection.run { screens.unwatch(id) }
    override suspend fun ack(frame: ScreenFrameAck) = connection.run { screens.ack(frame) }
    override suspend fun control(request: ScreenControlRequest) = connection.run { screens.control(request) }
    override suspend fun input(request: ScreenInputRequest) = connection.run { screens.input(request) }
    override suspend fun open(request: ScreenOpen) = connection.run { screens.open(request) }
    override suspend fun command(request: ScreenCommand) = connection.run { screens.command(request) }
}
