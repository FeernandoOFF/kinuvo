package dev.kinuvo.android.embedded

import kinuvo.app.connection.*
import kinuvo.server.embedded.AndroidBrowserRuntime
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kinuvo.server.BUILT_IN_FEATURES
import kinuvo.server.DeviceAuth
import kinuvo.server.embedded.EmbeddedServerHost
import kinuvo.server.embedded.browserPlugins
import kinuvo.server.embedded.embeddedPlugins
import kinuvo.server.NetworkListener
import kinuvo.server.devices.SharedPeers
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.koin.mp.KoinPlatform.getKoin
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import java.io.File

/**
 * A Server in the app's process; [ServerService] keeps it running, with its Routines, while the app is in the background.
 * When shared, it also listens on the network, so Devices on the same Wi-Fi or Tailscale network can pair with it.
 */
internal class AndroidEmbeddedServer(private val context: Context) : EmbeddedServer {
    private val browserRuntime by lazy { AndroidBrowserRuntime(context) }
    private val lock = Mutex()
    private var server: EmbeddedServerHost? = null
    private var listener: NetworkListener? = null
    private val prefs = context.getSharedPreferences("kinuvo-server", Context.MODE_PRIVATE)
    private val home = Path(context.filesDir.path, "Kinuvo")
    private val auth = DeviceAuth(File(home.toString(), "server-devices.json"))

    override val sharing: ServerSharing = object : ServerSharing {
            override val enabled get() = prefs.getBoolean(SHARED, false)
            override suspend fun share(enabled: Boolean) {
                prefs.edit().putBoolean(SHARED, enabled).apply()
                listen(host())
            }
    }

    override suspend fun endpoint(): Endpoint {
        val local = host()
        ServerService.start(context)
        return Endpoint("local://kinuvo", local.token, local::connect)
    }

    suspend fun host(): EmbeddedServerHost = lock.withLock {
        server ?: run {
            val browser = withContext(Dispatchers.Main.immediate) { browserRuntime.browserPlugins() }
            val embedded = embeddedPlugins(home, browser)
            EmbeddedServerHost.create(home, plugins = embedded.plugins, modelSources = embedded.modelSources, continuous = true, features = BUILT_IN_FEATURES, peers = { SharedPeers(it, auth) })
                .also { server = it; listenLocked(it) }
        }
    }

    private suspend fun listen(host: EmbeddedServerHost) = lock.withLock { listenLocked(host) }

    private suspend fun listenLocked(host: EmbeddedServerHost) {
        val shared = prefs.getBoolean(SHARED, false)
        auth.pairable = shared
        if (shared && listener == null) listener = NetworkListener(host.runtime, auth).also { withContext(Dispatchers.IO) { it.start("0.0.0.0", PORTS) } }
        if (!shared) listener?.let { withContext(Dispatchers.IO) { it.stop() }; listener = null }
    }

    private companion object {
        const val SHARED = "shared"
        val PORTS = (8787..8791).toList()
    }
}

class ServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Kinuvo Server is running")
            .setContentText("Agents on this phone keep working in the background")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        // Started again by Android after the process was ended, so the Server and its Routines come back too.
        (getKoin().get<EmbeddedServer>() as AndroidEmbeddedServer).let { server -> scope.launch { server.host() } }
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification)
        return START_STICKY
    }

    internal companion object {
        private const val CHANNEL = "server"
        private const val ID = 1
        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent) }
        }
    }
}

/** Binds the on-device Server for the shared Koin graph. */
val androidEmbeddedServerModule = module {
    single<EmbeddedServer> { AndroidEmbeddedServer(androidContext()) }
}
