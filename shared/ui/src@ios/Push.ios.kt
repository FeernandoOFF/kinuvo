package kinuvo.ui

import kinuvo.app.connection.*

import kinuvo.protocol.PushSubscription
import kinuvo.protocol.PushMessage
import kinuvo.protocol.PushKind
import kotlinx.coroutines.*
import org.koin.mp.KoinPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** APNs registration, implemented in Swift. */
interface NativePush {
    /** Registers with APNs, then reports this install's subscription through [relay] whenever it changes; null while Notifications aren't allowed. */
    fun start(relay: String, update: (PushSubscription?) -> Unit)
    /** Asks the user to allow Notifications, unless they've decided already. */
    fun requestPermission()
}

private var nativePush: NativePush? = null

fun installPush(native: NativePush) { nativePush = native }

/** APNs through the Push relay; the Notification Service Extension decrypts what arrives. */
internal class ApnsPushChannel(private val enabled: Boolean) : PushChannel, NotificationPermission {
    override val subscription: StateFlow<PushSubscription?>
        field = MutableStateFlow<PushSubscription?>(null)
    private var asked = false

    init {
        if (enabled) nativePush?.start(PUSH_RELAY) { subscription.value = it }
    }

    override fun request() {
        if (!enabled || asked) return
        asked = true
        nativePush?.requestPermission()
    }
}


fun notificationAction(serverId: String?, agentId: String?, sessionId: String?, approvalId: String?, action: String, text: String?, completion: (String?) -> Unit) {
    if (KoinPlatform.getKoinOrNull() == null) startKinuvo()
    val koin = KoinPlatform.getKoin()
    val actions = koin.get<NotificationActions>()
    CoroutineScope(Dispatchers.Main).launch {
        try {
            val selected = when (action) {
                "reply" -> NotificationAction.Reply(text.orEmpty())
                "allow" -> NotificationAction.Allow
                "deny" -> NotificationAction.Deny
                else -> return@launch completion(null)
            }
            val push = PushMessage("", "", agentId, sessionId, serverId = serverId,
                kind = if (action == "reply") PushKind.Reply else PushKind.Approval, approvalId = approvalId)
            completion(if (koin.getOrNull<MobileTestMode>()?.override == null && actions.perform(push, selected)) null else actions.failure(serverId))
        } catch (_: Exception) { completion(actions.failure(serverId)) }
    }
}
