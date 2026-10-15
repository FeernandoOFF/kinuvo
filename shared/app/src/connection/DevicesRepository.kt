package kinuvo.app.connection

import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DevicesState(val devices: List<PairedDeviceInfo>? = null, val error: String? = null)

class DevicesRepository(internal val connection: ConnectedServer) {
    val state: StateFlow<DevicesState>
        field = MutableStateFlow(DevicesState())

    private var attached: Job? = null

    internal fun attach(client: KinuvoClient) {
        detach()
        attached = connection.scope.launch {
            suspend fun load() {
                try { state.value = DevicesState(connection.call { it.call(Api.PairedDevices, Unit) }) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { state.value = state.value.copy(error = e.message) }
            }
            launch { client.events(Api.DevicesChanged).collect { load() } }
            client.state.collect {
                if (it is ConnectionState.Connected && client.lastConnect?.capabilities?.supports(ServerFeature.Pairing) == true) load()
            }
        }
    }

    internal fun detach() {
        attached?.cancel()
        state.value = DevicesState()
    }
}
