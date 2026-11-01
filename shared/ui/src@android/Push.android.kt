package kinuvo.ui

import kinuvo.app.connection.*

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kinuvo.protocol.PushMessage
import kinuvo.protocol.PushSubscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.mp.KoinPlatform.getKoin

/**
 * FCM through the Push relay. Firebase is set up from the `google_app_id`, `google_api_key`, `gcm_defaultSenderId` and
 * `project_id` string resources; while they're empty, this install has no push channel.
 */
internal class FcmPushChannel(private val context: Context, prefs: Preferences, enabled: Boolean) : PushChannel {
    override val subscription: StateFlow<PushSubscription?>
        field = MutableStateFlow<PushSubscription?>(null)

    val available = enabled && FirebaseApp.initializeApp(context) != null
    private val keys by lazy { PushKeys(prefs) }
    @Volatile private var token: String? = null

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

    fun open(record: String) = keys.open(record)
}

private var asked = false

/** Asks once per launch for the notification permission (Android 13+) when push is set up, and notices changes made in Settings. */
fun requestNotifications(activity: ComponentActivity) {
    val channel = getKoin().get<FcmPushChannel>()
    if (!channel.available) return
    val ask = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { channel.update() }
    activity.lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) channel.update() })
    if (Build.VERSION.SDK_INT >= 33 && !asked && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        asked = true
        ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

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
        val tag = push.sessionId ?: push.agentId
        val open = PendingIntent.getActivity(this, tag.hashCode(), intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, id)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(push.title)
            .setContentText(push.body)
            .setStyle(Notification.BigTextStyle().bigText(push.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(tag, 0, notification)
    }

    private companion object {
        const val URGENT = "urgent"
        const val ACTIVITY = "activity"
    }
}
