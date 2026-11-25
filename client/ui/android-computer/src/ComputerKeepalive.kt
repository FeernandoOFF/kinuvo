package kinuvo.ui

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kotlinx.coroutines.*

internal class ComputerKeepalive(context: Context) : Application.ActivityLifecycleCallbacks {
    private val app = context.applicationContext as Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var client: KinuvoClient? = null
    private var enabled = false
    private var visible = false
    private var started = false
    private var stop: suspend () -> Unit = {}
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { client?.reconnectNow() }
    }

    init {
        app.registerActivityLifecycleCallbacks(this)
        runCatching { app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network) }
        scope.launch { while (isActive) { update(); delay(500) } }
    }

    fun connection(client: KinuvoClient?, enabled: Boolean, stop: suspend () -> Unit) {
        this.client = client
        this.enabled = enabled
        this.stop = stop
        update()
    }

    private fun update() {
        val needed = enabled && client != null && ComputerService.instance != null &&
            (client?.state?.value as? ConnectionState.Disconnected)?.rejected != true
        val intent = Intent(app, ComputerKeepaliveService::class.java)
        if (!needed) {
            if (started) app.stopService(intent)
            started = false
            ComputerKeepaliveService.stop = null
        } else if (!started && visible) {
            ComputerKeepaliveService.stop = { scope.launch { stop() } }
            started = runCatching { app.startForegroundService(intent); true }.getOrDefault(false)
        }
    }

    override fun onActivityResumed(activity: Activity) { visible = true; update() }
    override fun onActivityPaused(activity: Activity) { visible = false }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

class ComputerKeepaliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stop?.invoke(); stopSelf(); return START_NOT_STICKY }
        if (stop == null) { stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("computer", "Computer use", NotificationManager.IMPORTANCE_LOW))
        val end = PendingIntent.getService(this, 0, Intent(this, ComputerKeepaliveService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "computer")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Kinuvo can use this phone")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", end).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(3, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(3, notification)
        } catch (_: SecurityException) { stopSelf() }
        return START_NOT_STICKY
    }

    internal companion object { var stop: (() -> Unit)? = null }
}
