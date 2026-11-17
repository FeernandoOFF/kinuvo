package kinuvo.app.connection


import kinuvo.app.agent.*
import kinuvo.protocol.FrameConnection
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.device.Links
import kinuvo.protocol.device.PairingCode
import kinuvo.protocol.device.TailcatPayload
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kinuvo.client.TailcatForwarder
import kinuvo.client.SharedTailcatForwarder
import kinuvo.client.UnsupportedTailcatForwarder
import kinuvo.client.tailcatConnection
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/** A Server this Device can connect to. */
sealed interface Server {
    /** Reached over the network; [url] is a URL or an opaque local key for Tailcat, whose secret [tailcatLink] stays in secure storage. [token] is null until paired. [name] is this Device's alias for it; [serverId] tells URLs to the same Server apart. */
    @Serializable
    data class Remote(val url: String, @Transient val token: String? = null, val name: String? = null, val serverId: String? = null, val transport: RemoteTransport = RemoteTransport.Url, @Transient val tailcatLink: String? = null) : Server {
        override fun toString() = "Remote(url=$url, name=$name, serverId=$serverId, transport=$transport)"
    }

    /** Runs inside this app, on platforms that can host one. */
    data object Embedded : Server
}

@Serializable
enum class RemoteTransport { Url, Tailcat }

/** Where a client connects; a null [token] means pairing with a code. */
data class Endpoint(val url: String, val token: String?, val connection: (suspend () -> FrameConnection)? = null)

/** Pairing choices: reachable base URLs, best first, and an optional secret [tailcatLink]. */
data class PairingInfo(val code: String, val expiresAt: Instant, val bases: List<String>, val management: Boolean = false, val tailcatLink: String? = null) {
    fun appLink(base: String) = Links.pair(base, code)
    fun webLink(base: String) = Links.webPair(base, code)
}

/** The bases another Device may use: how this one reached the Server at [url], unless that's this machine, then its LAN addresses. */
fun pairingInfo(url: String, pairing: PairingCode): PairingInfo {
    val secure = url.startsWith("wss://")
    val hostPort = url.substringAfter("://").substringBefore("/")
    val host = if (hostPort.startsWith('[')) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
    val own = "${if (secure) "https" else "http"}://$hostPort".takeUnless { host in LOOPBACK || url.startsWith("local:") || url.startsWith("tailcat:") }
    return PairingInfo(pairing.code, Instant.parse(pairing.expiresAt), (listOfNotNull(pairing.publicUrl, own.takeIf { pairing.publicUrl == null }) + pairing.addresses.takeIf { pairing.publicUrl == null }.orEmpty().map {
        val host = if (':' in it && !it.startsWith('[')) "[$it]" else it
        "http://$host:${pairing.port}"
    }).distinct(), pairing.management, pairing.tailcatInvite?.let(Links::tailcatPair))
}

private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]", "0.0.0.0")

/** Hosts a Server in-process; bound in DI only by apps that bundle the harness. */
interface EmbeddedServer {
    suspend fun endpoint(): Endpoint
    /** Lets other Devices reach this Server over the network; null where it can't. */
    val sharing: ServerSharing? get() = null
    val sharingOptions: Map<SharingOption, ServerSharing?> get() = mapOf(SharingOption.Lan to sharing, SharingOption.Tailcat to null)
}

/** An embedded Server listening on the local network and VPNs such as Tailscale, so other Devices can pair with it. */
enum class SharingOption { Lan, Tailcat }

sealed interface SharingState {
    data object Unavailable : SharingState
    data object Off : SharingState
    data object Starting : SharingState
    data object On : SharingState
    data class Error(val message: String) : SharingState
}

interface ServerSharing {
    val enabled: Boolean
    val state: SharingState get() = if (enabled) SharingState.On else SharingState.Off
    suspend fun share(enabled: Boolean)
    suspend fun reset() { error("This sharing option cannot be reset") }
}

/** The Agent and Session a device last had open. */
@Serializable
data class LastView(val agentId: AgentId, val sessionId: SessionId?)

/** Platform key-value storage: files on desktop, SharedPreferences on Android, NSUserDefaults on iOS. */
interface Preferences {
    operator fun get(key: String): String?
    operator fun set(key: String, value: String?)
    /** Removes every key this app saved. */
    fun clear()
}

/** The Servers this Device knows, the one it last used, and its last view. [transport] replaces the network for remote Servers, e.g. in UI tests. */
class Servers(
    private val prefs: Preferences,
    private val embedded: EmbeddedServer? = null,
    private val credentials: DeviceCredentials,
    private val transport: (suspend (url: String) -> FrameConnection)? = null,
    private val tailcat: TailcatForwarder = UnsupportedTailcatForwarder,
) {
    private val forwards = SharedTailcatForwarder(tailcat)
    private val remotes = ListSerializer(Server.Remote.serializer())

    val tailcatSupported get() = tailcat.supported

    /** Pairs this Device's tunnel with the Server that showed [invite]; [code] then pairs the Device itself over it. */
    class TailcatPaired(val server: Server.Remote, val code: String)

    suspend fun pairTailcat(invite: String, device: String, name: String? = null): TailcatPaired {
        require(Links.isTailcatInvite(invite)) { "Invalid Tailcat invite" }
        check(tailcat.supported) { "Tailcat connections are not supported on this platform yet" }
        val paired = tailcat.pair(invite, device)
        val code = try { ProtocolJson.decodeFromString(TailcatPayload.serializer(), paired.payload).code }
        catch (e: SerializationException) { throw IllegalStateException("The Server did not send a pairing code", e) }
        return TailcatPaired(Server.Remote("tailcat:${Uuid.random()}", name = name, transport = RemoteTransport.Tailcat, tailcatLink = paired.link), code)
    }

    val sharing get() = embedded?.sharingOptions?.get(SharingOption.Lan)
    val sharingOptions get() = SharingOption.entries.associateWith { embedded?.sharingOptions?.get(it) }

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
        is Server.Remote -> Endpoint(server.url, server.token, transport?.let { connect -> { connect(server.url) } }
            ?: if (server.transport == RemoteTransport.Tailcat) ({ tailcatConnection(requireNotNull(server.tailcatLink) { "Tailcat link missing; pair again" }, forwards) }) else null)
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
            val saved = server.copy(url = if (server.transport == RemoteTransport.Tailcat && known?.transport == RemoteTransport.Tailcat) known.url else server.url, token = token, serverId = serverId ?: known?.serverId, name = name)
            credentials.write(saved.url, token)
            saved.tailcatLink?.let { credentials.write("${saved.url}.link", it) }
            if (known != null && prefs[CURRENT] == known.url) prefs[CURRENT] = saved.url
            saveRemotes(all.filterNot(::same) + saved)
            all.filter { same(it) && it.url != saved.url }.forEach { deleteCredentials(it.url) }
            saved
        }
        Server.Embedded -> server.also { serverId?.let { prefs[EMBEDDED_ID] = it } }
    }

    /** The saved Server with [serverId], including this Device's own; null when it isn't one of them. */
    fun find(serverId: String): Server? = runCatching { remotes() }.getOrDefault(emptyList()).firstOrNull { it.serverId == serverId }
        ?: Server.Embedded.takeIf { embedded != null && prefs[EMBEDDED_ID] == serverId }

    /** Sets [server]'s alias; blank clears it. */
    fun rename(server: Server.Remote, name: String): Server.Remote {
        val renamed = server.copy(name = name.trim().ifEmpty { null })
        saveRemotes(remotes().map { if (it.url == server.url) it.copy(name = renamed.name) else it })
        return renamed
    }

    /** Drops [server] and, when it was the current one, the view on it. */
    fun forget(server: Server.Remote) {
        saveRemotes(remotes().filterNot { it.url == server.url })
        deleteCredentials(server.url)
        if (prefs[CURRENT] == server.url) {
            prefs[CURRENT] = null
            lastView = null
        }
    }

    /** Forgets every Server and its credential, and every preference, as on a fresh install. Servers aren't told. */
    fun reset() {
        remotes().forEach { deleteCredentials(it.url) }
        prefs.clear()
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
        return stored.map { it.copy(token = credentials.read(it.url), tailcatLink = if (it.transport == RemoteTransport.Tailcat) credentials.read("${it.url}.link") else null) }
    }

    private fun deleteCredentials(key: String) { credentials.delete(key); credentials.delete("$key.link") }

    private fun saveRemotes(list: List<Server.Remote>) {
        prefs[SERVERS] = Json.encodeToString(remotes, list)
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T? =
        runCatching { Json.decodeFromString(serializer, text) }.getOrNull()

    private companion object {
        const val CURRENT = "current"
        const val SERVERS = "servers"
        const val VIEW = "view"
        const val EMBEDDED = "embedded"
        const val EMBEDDED_ID = "embedded.serverId"
        const val PAIRING_BASE = "pairingBase."
    }
}

/** Shown when choosing a Server: its alias, else its address. */
val Server.label: String
    get() = when (this) {
        is Server.Remote -> name ?: address
        Server.Embedded -> "This device"
    }

val Server.Remote.address: String get() = if (transport == RemoteTransport.Tailcat) "Tailcat Server" else url.substringAfter("://").substringBefore("/")

/** Tells saved Servers apart; stable across renames and new tokens. */
val Server.key: String
    get() = when (this) {
        is Server.Remote -> url
        Server.Embedded -> "embedded"
    }
