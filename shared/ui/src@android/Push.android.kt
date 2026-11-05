package kinuvo.ui

import kinuvo.app.connection.*

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.PushKind
import kinuvo.protocol.PushMessage
import kinuvo.protocol.PushSubscription
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.mp.KoinPlatform.getKoin

/**
 * FCM through the Push relay. Firebase is set up from the `google_app_id`, `google_api_key`, `gcm_defaultSenderId` and
 * `project_id` string resources; while they're empty, this install has no push channel and asks for nothing.
 */
internal class FcmPushChannel(private val context: Context, private val prefs: Preferences, enabled: Boolean) : PushChannel, NotificationPermission {
    override val subscription: StateFlow<PushSubscription?>
        field = MutableStateFlow<PushSubscription?>(null)

    val available = enabled && FirebaseApp.initializeApp(context) != null
    private val keys by lazy { PushKeys(prefs) }
    @Volatile private var token: String? = null
    private var ask: ActivityResultLauncher<String>? = null

    // FCM registration tokens are deprecated for Installation IDs (register/onRegistered), which the relay can't address yet.
    init {
        @Suppress("DEPRECATION")
        if (available) FirebaseMessaging.getInstance().token.addOnSuccessListener(::update)
    }

    /** Call when FCM hands out a [token] or the user may have changed the notification permission. */
    fun update(token: String? = this.token) {
        this.token = token
        val allowed = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        subscription.value = token?.takeIf { available && allowed }?.let { keys.subscription("$PUSH_RELAY/fcm/$it") }
    }

    /** Lets [request] ask from [activity], and notices permission changes made in Settings while it's shown. */
    fun attach(activity: ComponentActivity) {
        if (!available) return
        val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { update() }
        ask = launcher
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) update()
            if (event == Lifecycle.Event.ON_DESTROY && ask === launcher) ask = null
        })
    }

    /** Asks once per install (Android 13+), while an activity is attached. */
    override fun request() {
        if (!available || Build.VERSION.SDK_INT < 33 || prefs[ASKED] != null) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val launcher = ask ?: return
        prefs[ASKED] = "true"
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun open(record: String) = keys.open(record)

    private companion object {
        const val ASKED = "notifications.asked"
    }
}

/** Lets the app ask for Notifications from [activity] once a chat opens; call from its `onCreate`. */
fun attachNotifications(activity: ComponentActivity) = getKoin().get<FcmPushChannel>().attach(activity)

/** Receives FCM tokens and pushed Notifications, which it decrypts and shows. */
class KinuvoMessagingService : FirebaseMessagingService() {
    private val channel get() = getKoin().get<FcmPushChannel>()

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) = channel.update(token)

    override fun onMessageReceived(message: RemoteMessage) {
        message.data["k"]?.let(channel::open)?.let(::show)
    }

    private fun show(push: PushMessage) {
        val manager = getSystemService(NotificationManager::class.java)
        val id = if (push.urgent) URGENT else ACTIVITY
        manager.createNotificationChannel(
            if (push.urgent) NotificationChannel(URGENT, "Approvals and questions", NotificationManager.IMPORTANCE_HIGH)
            else NotificationChannel(ACTIVITY, "Replies and Routines", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent = push.link?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(packageName) }
            ?: packageManager.getLaunchIntentForPackage(packageName) ?: return
        val tag = notificationTag(push)
        val open = PendingIntent.getActivity(this, tag.hashCode(), intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, id)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(push.title)
            .setContentText(push.body)
            .setStyle(Notification.BigTextStyle().bigText(push.body))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (push.serverId != null) {
            val actions = when {
                push.kind == PushKind.Reply && push.agentId != null && push.sessionId != null -> listOf("reply" to "Reply")
                push.kind == PushKind.Approval && push.approvalId != null && Build.VERSION.SDK_INT >= 31 -> listOf("allow" to "Allow", "deny" to "Deny")
                else -> emptyList()
            }
            for ((action, title) in actions) {
                val actionIntent = Intent(this, NotificationActionReceiver::class.java)
                    .setData(Uri.parse("kinuvo-action://${Uri.encode(tag)}/$action"))
                    .putExtra("push", ProtocolJson.encodeToString(PushMessage.serializer(), push))
                    .putExtra("action", action)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or when {
                    action != "reply" -> PendingIntent.FLAG_IMMUTABLE
                    Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_MUTABLE
                    else -> 0
                }
                val pending = PendingIntent.getBroadcast(this, 0, actionIntent, flags)
                val button = Notification.Action.Builder(null, title, pending)
                if (action == "reply") button.addRemoteInput(RemoteInput.Builder("reply").setLabel("Message").build())
                else if (Build.VERSION.SDK_INT >= 31) button.setAuthenticationRequired(true)
                builder.addAction(button.build())
            }
        }
        manager.notify(tag, 0, builder.build())
    }

    private companion object {
        const val URGENT = "urgent"
        const val ACTIVITY = "activity"
    }
}


private fun notificationTag(push: PushMessage) =
    listOf(push.serverId, push.agentId, push.sessionId, push.approvalId).joinToString(":")

/** Runs without opening an Activity; explicit PendingIntents are the only entry point. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val push = runCatching { ProtocolJson.decodeFromString(PushMessage.serializer(), intent.getStringExtra("push") ?: return) }.getOrNull() ?: return
        val action = when (intent.getStringExtra("action")) {
            "reply" -> NotificationAction.Reply(RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")?.toString().orEmpty())
            "allow" -> NotificationAction.Allow
            "deny" -> NotificationAction.Deny
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val koin = getKoin()
                val actions = koin.get<NotificationActions>()
                val success = koin.getOrNull<MobileTestMode>()?.override == null &&
                    withTimeoutOrNull(8_000) { actions.perform(push, action) } == true
                val text = if (!success) actions.failure(push.serverId) else when (action) {
                    is NotificationAction.Reply -> "Sent"
                    NotificationAction.Allow -> "Allowed"
                    NotificationAction.Deny -> "Denied"
                }
                val manager = context.getSystemService(NotificationManager::class.java)
                val tag = notificationTag(push)
                val current = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == 0 } ?: return@launch
                val updated = Notification.Builder.recoverBuilder(context, current.notification)
                    .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                    .setActions().build()
                manager.notify(tag, 0, updated)
            } finally { pending.finish() }
        }
    }
}
