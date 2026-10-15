package kinuvo.app.connection

import kinuvo.app.agent.*

import kinuvo.protocol.FrameConnection
import kinuvo.protocol.Links
import kinuvo.protocol.PairingCode
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A Server this Device can connect to. */
sealed interface Server {
    /** Reached over the network; [token] is null until paired. [name] is this Device's alias for it; [serverId] tells URLs to the same Server apart. */
    @Serializable
    data class Remote(val url: String, @Transient val token: String? = null, val name: String? = null, val serverId: String? = null) : Server

    /** Runs inside this app, on platforms that can host one. */
    data object Embedded : Server
}

/** Where a client connects; a null [token] means pairing with a code. */
data class Endpoint(val url: String, val token: String?, val connection: (suspend () -> FrameConnection)? = null)

/** What another Device needs to pair with a Server: the [code] and the base URLs it may reach the Server at, best first. */
data class PairingInfo(val code: String, val expiresAt: Instant, val bases: List<String>, val management: Boolean = false) {
    fun appLink(base: String) = Links.pair(base, code)
    fun webLink(base: String) = Links.webPair(base, code)
}

/** The bases another Device may use: how this one reached the Server at [url], unless that's this machine, then its LAN addresses. */
fun pairingInfo(url: String, pairing: PairingCode): PairingInfo {
    val secure = url.startsWith("wss://")
    val hostPort = url.substringAfter("://").substringBefore("/")
    val host = if (hostPort.startsWith('[')) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
    val own = "${if (secure) "https" else "http"}://$hostPort".takeUnless { host in LOOPBACK || url.startsWith("local:") }
    return PairingInfo(pairing.code, Instant.parse(pairing.expiresAt), (listOfNotNull(pairing.publicUrl, own.takeIf { pairing.publicUrl == null }) + pairing.addresses.takeIf { pairing.publicUrl == null }.orEmpty().map {
        val host = if (':' in it && !it.startsWith('[')) "[$it]" else it
        "http://$host:${pairing.port}"
    }).distinct(), pairing.management)
}

private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]", "0.0.0.0")

/** Hosts a Server in-process; bound in DI only by apps that bundle the harness. */
interface EmbeddedServer {
    suspend fun endpoint(): Endpoint
    /** Lets other Devices reach this Server over the network; null where it can't. */
    val sharing: ServerSharing? get() = null
}

/** An embedded Server listening on the local network and VPNs such as Tailscale, so other Devices can pair with it. */
interface ServerSharing {
    val enabled: Boolean
    suspend fun share(enabled: Boolean)
}

/** The Agent and Session a device last had open. */
@Serializable
data class LastView(val agentId: String, val sessionId: String?)

/** Platform key-value storage: files on desktop, SharedPreferences on Android, NSUserDefaults on iOS. */
interface Preferences {
    operator fun get(key: String): String?
    operator fun set(key: String, value: String?)
}

/** The Servers this Device knows, the one it last used, and its last view. */
class Servers(private val prefs: Preferences, private val embedded: EmbeddedServer? = null, private val credentials: DeviceCredentials) {
    private val remotes = ListSerializer(Server.Remote.serializer())

    val sharing get() = embedded?.sharing

    val all: List<Server> get() = listOfNotNull(Server.Embedded.takeIf { embedded != null }) + remotes()

    /** The last Server connected to; the embedded one when nothing was chosen yet. */
    val current: Server?
        get() = when (val key = prefs[CURRENT]) {
            null -> all.firstOrNull { it is Server.Embedded }
            EMBEDDED -> Server.Embedded.takeIf { embedded != null }
            else -> remotes().firstOrNull { it.url == key }
        }

    var onboardingComplete: Boolean
        get() = prefs["onboarding.complete"]?.toBooleanStrictOrNull() ?: false
        set(value) { prefs["onboarding.complete"] = value.toString() }

    var lastView: LastView?
        get() = prefs[VIEW]?.let { decode(LastView.serializer(), it) }
        set(value) { prefs[VIEW] = value?.let { Json.encodeToString(LastView.serializer(), it) } }

    suspend fun endpoint(server: Server): Endpoint = when (server) {
        is Server.Remote -> Endpoint(server.url, server.token)
        Server.Embedded -> requireNotNull(embedded) { "No embedded server on this device" }.endpoint()
    }

    /**
     * Stores the token [server] handed out; returns the Server to use from now on. A Server already saved under another
     * URL (same [serverId]) is replaced, keeping its alias; a new one is named [serverName].
     */
    fun remember(server: Server, token: String, serverId: String? = null, serverName: String? = null): Server = when (server) {
        is Server.Remote -> {
            val all = remotes()
            fun same(it: Server.Remote) = it.url == server.url || serverId != null && it.serverId == serverId
            val known = all.firstOrNull { it.url == server.url } ?: all.firstOrNull(::same)
            val name = if (known?.serverId != null) known.name else known?.name ?: server.name ?: serverName
            val saved = server.copy(token = token, serverId = serverId ?: known?.serverId, name = name)
            credentials.write(saved.url, token)
            saveRemotes(all.filterNot(::same) + saved)
            all.filter { same(it) && it.url != saved.url }.forEach { credentials.delete(it.url) }
            saved
        }
        Server.Embedded -> server
    }

    /** Sets [server]'s alias; blank clears it. */
    fun rename(server: Server.Remote, name: String): Server.Remote {
        val renamed = server.copy(name = name.trim().ifEmpty { null })
        saveRemotes(remotes().map { if (it.url == server.url) it.copy(name = renamed.name) else it })
        return renamed
    }

    /** Drops [server] and, when it was the current one, the view on it. */
    fun forget(server: Server.Remote) {
        saveRemotes(remotes().filterNot { it.url == server.url })
        credentials.delete(server.url)
        if (prefs[CURRENT] == server.url) {
            prefs[CURRENT] = null
            lastView = null
        }
    }

    fun unpair(server: Server.Remote): Server.Remote = server.copy(token = null).also { unpaired ->
        saveRemotes(remotes().map { if (it.url == server.url) unpaired else it })
        credentials.delete(server.url)
    }

    /** The pairing base last shown for the Server with [serverId]. */
    fun pairingBase(serverId: String): String? = prefs["$PAIRING_BASE$serverId"]

    fun setPairingBase(serverId: String, base: String) {
        prefs["$PAIRING_BASE$serverId"] = base
    }

    fun select(server: Server) {
        prefs[CURRENT] = when (server) {
            is Server.Remote -> server.url
            Server.Embedded -> EMBEDDED
        }
    }

    private fun remotes(): List<Server.Remote> {
        val stored = prefs[SERVERS]?.let { decode(remotes, it) }.orEmpty()
        return stored.map { it.copy(token = credentials.read(it.url)) }
    }

    private fun saveRemotes(list: List<Server.Remote>) {
        prefs[SERVERS] = Json.encodeToString(remotes, list)
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, text: String): T? =
        runCatching { Json.decodeFromString(serializer, text) }.getOrNull()

    private companion object {
        const val CURRENT = "current"
        const val SERVERS = "servers"
        const val VIEW = "view"
        const val EMBEDDED = "embedded"
        const val PAIRING_BASE = "pairingBase."
    }
}

/** Shown when choosing a Server: its alias, else its address. */
val Server.label: String
    get() = when (this) {
        is Server.Remote -> name ?: address
        Server.Embedded -> "This device"
    }

val Server.Remote.address: String get() = url.substringAfter("://").substringBefore("/")

/** Tells saved Servers apart; stable across renames and new tokens. */
val Server.key: String
    get() = when (this) {
        is Server.Remote -> url
        Server.Embedded -> "embedded"
    }
