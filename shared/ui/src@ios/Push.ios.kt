package kinuvo.ui

import kinuvo.app.connection.*

import kinuvo.protocol.PushSubscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** APNs registration, implemented in Swift. */
interface NativePush {
    /** Asks for Notifications, then reports this install's subscription through [relay] whenever it changes. */
    fun start(relay: String, update: (PushSubscription?) -> Unit)
}

private var nativePush: NativePush? = null

fun installPush(native: NativePush) { nativePush = native }

/** APNs through the Push relay; the Notification Service Extension decrypts what arrives. */
internal class ApnsPushChannel(enabled: Boolean) : PushChannel {
    override val subscription: StateFlow<PushSubscription?>
        field = MutableStateFlow<PushSubscription?>(null)

    init {
        if (enabled) nativePush?.start(PUSH_RELAY) { subscription.value = it }
    }
}
