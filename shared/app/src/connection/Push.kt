package kinuvo.app.connection

import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.PushParams
import kinuvo.protocol.PushSubscription
import kinuvo.protocol.ServerFeature
import kinuvo.protocol.supports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The hosted Push relay that forwards to APNs and FCM; see ADR 0018. */
const val PUSH_RELAY = "https://kinuvo.feernandooff.com/v1"

/** This install's push channel (APNs or FCM): null until the OS hands out a token, or after the user turns Notifications off. */
interface PushChannel {
    val subscription: StateFlow<PushSubscription?>
}

/** Tells each Server this Device connects to where to push its Notifications. */
class PushRegistration(private val connection: ConnectedServer, private val channel: PushChannel?) {
    private var attached: Job? = null

    internal fun attach(client: KinuvoClient) {
        detach()
        val channel = channel ?: return
        attached = connection.scope.launch {
            combine(client.state, channel.subscription) { state, subscription ->
                (state is ConnectionState.Connected && client.lastConnect?.capabilities.supports(ServerFeature.Push)) to subscription
            }.distinctUntilChanged().collect { (ready, subscription) ->
                if (!ready) return@collect
                try { client.call(Api.SubscribePush, PushParams(subscription)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {} // Retried on the next connect.
            }
        }
    }

    internal fun detach() { attached?.cancel() }
}
