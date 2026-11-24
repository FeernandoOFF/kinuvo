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
import kinuvo.plugins.exec.prootExecPlugin
import kinuvo.plugins.exec.prootLinux
import kinuvo.harness.acp.AcpExecutionFactory
import kinuvo.server.embedded.browserPlugins
import kinuvo.server.store.AgentFiles
import kinuvo.server.embedded.embeddedPlugins
import kinuvo.server.NetworkSecurity
import kinuvo.server.embedded.eraseEmbeddedServer
import kinuvo.server.embedded.EmbeddedSharing
import kinuvo.client.TailcatForwarder
import kinuvo.tailcat.Tailcat
import kinuvo.server.NetworkListener
import kinuvo.server.devices.SharedPeers
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.koin.mp.KoinPlatform.getKoin
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

/**
 * A Server in the app's process; [ServerService] keeps it running, with its Routines, while the app is in the background.
 * When shared, it also listens on the network, so Devices on the same Wi-Fi or Tailscale network can pair with it.
 */
internal class AndroidEmbeddedServer(private val context: Context) : EmbeddedServer {
    private val browserRuntime by lazy { AndroidBrowserRuntime(context) }
    private val lock = Mutex()
    private val setup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: EmbeddedServerHost? = null
    private var listener: NetworkListener? = null
    private val prefs = context.getSharedPreferences("kinuvo-server", Context.MODE_PRIVATE)
    private val home = Path(context.filesDir.path, "Kinuvo")
    private val auth = DeviceAuth(Path(home, "server-devices.json"))

    private val tailcat get() = Tailcat.takeIf { it.supported }
    private var tailcatListener: NetworkListener? = null
    init { auth.offlineTailcatRevoke = { phone -> tailcat?.revoke(Path(home.toString(), "tailcat"), phone) } }
    private val sharingHost = EmbeddedSharing(
        Path(home.toString(), "tailcat"), Build.MODEL, { tailcat },
        { prefs.getBoolean(if (it) TAILCAT else SHARED, false) },
        { tailcat, enabled -> prefs.edit().putBoolean(if (tailcat) TAILCAT else SHARED, enabled).apply() },
        { enabled -> listenLocked(requireNotNull(server), enabled) },
        {
            val listener = NetworkListener(requireNotNull(server).runtime, auth, NetworkSecurity(trustedProxies = emptySet()), remoteOnly = true)
            tailcatListener = listener
            listener.start("127.0.0.1", emptyList())
        },
        { tailcatListener?.stop(); tailcatListener = null },
        { tunnel, pairable -> auth.tailcat(tunnel); auth.pairable = pairable },
    )

    override val sharing: ServerSharing = object : ServerSharing {
        override val enabled get() = prefs.getBoolean(SHARED, false)
        override suspend fun share(enabled: Boolean) { host(); sharingHost.shareLan(enabled) }
    }
    private val tailcatSharing = object : ServerSharing {
        override val enabled get() = sharingHost.enabled
        override val state get() = when (sharingHost.state) {
            EmbeddedSharing.State.Unavailable -> SharingState.Unavailable
            EmbeddedSharing.State.Off -> SharingState.Off
            EmbeddedSharing.State.Starting -> SharingState.Starting
            EmbeddedSharing.State.On -> SharingState.On
            EmbeddedSharing.State.Error -> SharingState.Error("Could not start Tailcat; try again")
        }
        override suspend fun share(enabled: Boolean) { host(); sharingHost.share(enabled) }
        override suspend fun reset() { host(); sharingHost.reset() }
    }
    override val sharingOptions get() = mapOf(SharingOption.Lan to sharing, SharingOption.Tailcat to tailcatSharing)

    override suspend fun erase() = lock.withLock {
        sharingHost.close()
        listener?.stop()
        listener = null
        server?.close()
        server = null
        eraseEmbeddedServer(home)
    }

    override suspend fun endpoint(): Endpoint {
        val local = host()
        ServerService.start(context)
        return Endpoint("local://kinuvo", local.token, local::connect)
    }

    suspend fun host(): EmbeddedServerHost = lock.withLock {
        server ?: run {
            val browser = withContext(Dispatchers.Main.immediate) { browserRuntime.browserPlugins() }
            val linux = prootLinux(context)?.also { linux -> setup.launch { runCatching { linux.ensure() } } }
            val embedded = embeddedPlugins(home, browser + listOfNotNull(linux?.let { prootExecPlugin(it, AgentFiles(home)) }))
            EmbeddedServerHost.create(home, plugins = embedded.plugins, modelSources = embedded.modelSources, continuous = true, features = BUILT_IN_FEATURES, peers = { SharedPeers(it, auth) },
                executionFactory = linux?.let { AcpExecutionFactory(launcher = prootAcpLauncher(it)) })
                .also {
                    server = it
                    sharingHost.restore()
                }
        }
    }

    suspend fun close() = lock.withLock {
        try { sharingHost.close() } finally { server?.close(); server = null }
    }

    private suspend fun listenLocked(host: EmbeddedServerHost, shared: Boolean) {
        if (shared && listener == null) listener = NetworkListener(host.runtime, auth).also { it.start("0.0.0.0", PORTS) }
        if (!shared) listener?.let { it.stop(); listener = null }
    }

    private companion object {
        const val SHARED = "shared"
        const val TAILCAT = "tailcat"
        val PORTS = (8787..8791).toList()
    }
}

class ServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onDestroy() {
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { (getKoin().get<EmbeddedServer>() as AndroidEmbeddedServer).close() }
        super.onDestroy()
    }
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
    single<TailcatForwarder> { Tailcat }
}
