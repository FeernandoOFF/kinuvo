package kinuvo.ui

import kinuvo.app.connection.*

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.push.PushKind
import kinuvo.protocol.push.PushMessage
import kinuvo.protocol.push.PushSubscription
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

/** Runs a Notification's Reply, Allow or Deny for the Swift delegate; [completion] gets a message when it failed, else null. */
fun notificationAction(serverId: String?, agentId: String?, sessionId: String?, approvalId: String?, action: String, text: String?, completion: (String?) -> Unit) {
    val koin = KoinPlatform.getKoin()
    val actions = koin.get<NotificationActions>()
    val selected = when (action) {
        "reply" -> NotificationAction.Reply(text.orEmpty())
        "allow" -> NotificationAction.Allow
        "deny" -> NotificationAction.Deny
        else -> return completion(null)
    }
    val push = PushMessage("", "", agentId?.let(::AgentId), sessionId?.let(::SessionId), serverId = serverId,
        kind = if (action == "reply") PushKind.Reply else PushKind.Approval, approvalId = approvalId?.let(::InteractionId))
    CoroutineScope(Dispatchers.Main).launch {
        val done = koin.getOrNull<MobileTestMode>()?.override == null && actions.perform(push, selected)
        completion(if (done) null else actions.failure(serverId))
    }
}
