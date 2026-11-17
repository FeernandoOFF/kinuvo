package kinuvo.server

import io.github.oshai.kotlinlogging.KotlinLogging
import kinuvo.server.devices.*
import kinuvo.server.plugin.TailcatInvite
import kinuvo.server.plugin.TailcatTunnel
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.device.*
import kinuvo.protocol.push.*
import kinuvo.protocol.session.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.days

@Serializable
data class PairedDevice(val name: String, val tokenSha256: String, val pairedAt: String, val tools: Set<DeviceToolKind> = emptySet(), val lastSeen: String? = null,
    val id: String, val management: Boolean, val expiresAt: String, val push: PushSubscription? = null, val tailcatPhone: String? = null)

@Serializable
private data class DevicesFile(val devices: List<PairedDevice> = emptyList())

private val log = KotlinLogging.logger {}
private const val MIN_INVITE_SECONDS = 10

class DeviceAuth(private val file: File, private val now: () -> Instant = { Clock.System.now() }) : PeerRegistry {
    private val random = SecureRandom()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val sessionTokens = mutableSetOf<String>()
    /** The app's own connections to the Server it embeds, which share their Device tools like paired Devices. */
    private val sessionDevices = mutableMapOf<String, ToolDevice>()
    var onNewCode: ((String) -> Unit)? = null
    var port = 0
    var addresses: () -> List<String> = { emptyList() }
    private val tailcatLock = Any()
    private var tunnel: TailcatTunnel? = null
    private var window: TailcatWindow? = null
    /** Unpairs a phone from the private tunnel when its Device is revoked while no tunnel is serving. */
    @Volatile var offlineTailcatRevoke: (String) -> Unit = {}
    val tailcatActive get() = synchronized(tailcatLock) { tunnel != null }
    var publicUrl: String? = null
    var learnedUrl: () -> String? = { null }
    @Volatile override var pairable = true
    @Volatile var pairingCode = ""; private set
    private var codeExpires = Instant.DISTANT_PAST
    private var codeManagement = false
    private var attempts = 0

    override fun authenticate(device: String, token: String?, pairingCode: String?) =
        authenticate(device, token, pairingCode, null)

    @Synchronized
    override fun authenticate(device: String, token: String?, pairingCode: String?, tailcatPeer: String?): String? {
        if (token != null && token in sessionTokens) return token.also {
            // A reconnect with a new session token replaces the app's earlier one rather than adding another Device.
            sessionDevices.entries.removeIf { (other, own) -> other != it && own.name == device.take(80) }
            sessionDevices[it] = (sessionDevices[it] ?: ToolDevice(identity(sha256(it)), "", emptySet())).copy(name = device.take(80))
        }
        if (token != null) return token.takeIf { valid(it) }.also { if (it != null) seen(it) }
        if (!pairable || pairingCode == null || this.pairingCode.isEmpty() || now() >= codeExpires) return null
        if (++attempts > 10) { closePairing(); return null }
        if (!MessageDigest.isEqual(pairingCode.toByteArray(), this.pairingCode.toByteArray())) { if (attempts >= 10) closePairing(); return null }
        val newToken = randomToken()
        val time = now()
        val phone = synchronized(tailcatLock) { tunnel?.phoneOf(this.pairingCode)?.takeIf { it == tailcatPeer } }
        save(devices() + PairedDevice(device.take(80), sha256(newToken), time.toString(), lastSeen = time.toString(),
            id = identity(sha256(newToken)), management = codeManagement, expiresAt = (time + 90.days).toString(), tailcatPhone = phone))
        closePairing()
        return newToken
    }

    @Synchronized
    override fun valid(token: String): Boolean {
        if (token in sessionTokens) return true
        return devices().any { it.tokenSha256 == sha256(token) && unexpired(it) }
    }

    @Synchronized
    override fun management(token: String) = token in sessionTokens || device(token)?.management == true

    @Synchronized
    fun currentPairingCode(): String {
        if (!pairable) error("Pairing is disabled")
        if (pairingCode.isEmpty() || now() >= codeExpires) refreshCode(management = devices().isEmpty())
        return pairingCode
    }

    override fun pairing(refresh: Boolean, management: Boolean): PairingCode? {
        // Opening the invite can wait on Tailcat startup, so it runs outside this monitor.
        val (current, expires, bound) = synchronized(this) {
            if (!pairable) return null
            if (refresh || pairingCode.isEmpty() || now() >= codeExpires || codeManagement != management) refreshCode(management)
            Triple(PairingCode(pairingCode, codeExpires.toString(), port, addresses(), publicUrl ?: learnedUrl(), codeManagement), codeExpires,
                devices().mapNotNull { it.tailcatPhone }.toSet())
        }
        return current.copy(tailcatInvite = tailcatInvite(current.code, expires, bound))
    }

    /** Serves pairing through [tunnel] from now on, or stops when null; the caller closes the tunnel it passed earlier. */
    fun tailcat(tunnel: TailcatTunnel?) = synchronized(tailcatLock) {
        window?.invite?.let { runCatching { it.close() } }
        window = null
        this.tunnel = tunnel
    }

    internal fun tailcatPeer(port: Int): String? = synchronized(tailcatLock) { tunnel?.peerAt(port) }

    private class TailcatWindow(val code: String, val invite: TailcatInvite)

    private fun tailcatInvite(code: String, expires: Instant, bound: Set<String>): String? = synchronized(tailcatLock) {
        val tunnel = tunnel ?: return null
        window?.takeIf { it.code == code && it.invite.open }?.let { return it.invite.text }
        window?.invite?.let { runCatching { it.close() } }
        window = null
        val seconds = (expires - now()).inWholeSeconds.toInt()
        if (seconds < MIN_INVITE_SECONDS) return null
        val invite = try { tunnel.invite(code, ProtocolJson.encodeToString(TailcatPayload.serializer(), TailcatPayload(code)), seconds, bound) }
        catch (e: Exception) { log.warn { "Could not open a Tailcat pairing window: ${e.message}" }; return null }
        window = TailcatWindow(code, invite)
        invite.text
    }

    @Synchronized
    override fun paired(token: String): List<PairedDeviceInfo> {
        val current = device(token)?.id
        return devices().map { PairedDeviceInfo(it.id, it.name, it.pairedAt, it.lastSeen, current = it.id == current,
            tools = it.tools, management = it.management, expiresAt = it.expiresAt, push = it.push != null) }
    }

    override fun revoke(id: String): Boolean {
        val phone = synchronized(this) {
            val devices = devices()
            val kept = devices.filterNot { it.id == id }
            if (kept.size == devices.size) return false
            save(kept)
            devices.first { it.id == id }.tailcatPhone
        }
        if (phone != null) synchronized(tailcatLock) {
            try { tunnel?.revoke(phone) ?: offlineTailcatRevoke(phone) }
            catch (e: Exception) { log.warn { "Could not revoke a Tailcat phone: ${e.message}" } }
        }
        return true
    }

    @Synchronized
    fun sessionToken(): String = randomToken().also { sessionTokens += it }

    @Synchronized
    fun refreshCode(management: Boolean = false): String {
        check(pairable) { "Pairing is disabled" }
        pairingCode = randomToken()
        codeExpires = now() + 10.minutes
        codeManagement = management
        attempts = 0
        onNewCode?.invoke(pairingCode)
        return pairingCode
    }

    private fun closePairing() { pairingCode = ""; codeExpires = Instant.DISTANT_PAST; attempts = 0 }

    @Synchronized
    fun cookieMaxAge(token: String): Int? = device(token)?.takeIf(::unexpired)?.let {
        (Instant.parse(it.expiresAt) - now()).inWholeSeconds.toInt()
    }

    @Synchronized
    override fun rotate(token: String): String {
        check(valid(token)) { "Device access expired" }
        val device = requireNotNull(device(token)) { "This credential cannot be rotated" }
        val replacement = randomToken()
        save(devices().map { if (it.id == device.id) it.copy(tokenSha256 = sha256(replacement), expiresAt = (now() + 90.days).toString()) else it })
        return replacement
    }

    @Synchronized
    fun devices(): List<PairedDevice> =
        if (file.isFile) json.decodeFromString(DevicesFile.serializer(), file.readText()).devices else emptyList()

    @Synchronized
    override fun toolDevices(): List<ToolDevice> = devices().filter(::unexpired).map { ToolDevice(it.id, it.name, it.tools) } + sessionDevices.values.filter { it.tools.isNotEmpty() }

    @Synchronized
    override fun setTools(token: String, tools: Set<DeviceToolKind>): String {
        check(valid(token)) { "Device access expired" }
        sessionDevices[token]?.let { own -> sessionDevices[token] = own.copy(tools = tools); return own.id }
        val device = device(token)
        if (device == null) require(tools.isEmpty()) { "Pair this Device before enabling its tools" }
        else save(devices().map { if (it.id == device.id) it.copy(tools = tools) else it })
        return device?.id ?: identity(sha256(token))
    }

    override val pushable = true

    @Synchronized
    override fun setPush(token: String, subscription: PushSubscription?) {
        check(valid(token)) { "Device access expired" }
        val device = device(token) ?: return
        subscription?.let { require(it.endpoint.startsWith("https://")) { "Push endpoints must use HTTPS" } }
        // One Device per endpoint: a reinstall or re-pair takes it over.
        save(devices().map { when {
            it.id == device.id -> it.copy(push = subscription)
            it.push?.endpoint == subscription?.endpoint -> it.copy(push = null)
            else -> it
        } })
    }

    @Synchronized
    override fun pushSubscriptions() = devices().filter(::unexpired).mapNotNull { it.push }

    @Synchronized
    override fun dropPush(endpoint: String) {
        val devices = devices()
        if (devices.any { it.push?.endpoint == endpoint }) save(devices.map { if (it.push?.endpoint == endpoint) it.copy(push = null) else it })
    }

    private fun device(token: String): PairedDevice? {
        val hash = sha256(token)
        return devices().firstOrNull { it.tokenSha256 == hash }
    }
    private fun unexpired(device: PairedDevice) = now() < Instant.parse(device.expiresAt)
    private fun seen(token: String) {
        val device = device(token) ?: return
        val time = now()
        if (device.lastSeen?.let { time - Instant.parse(it) < 1.minutes } != true)
            save(devices().map { if (it.id == device.id) it.copy(lastSeen = time.toString()) else it })
    }
    private fun identity(hash: String) = sha256("device:$hash").take(24)
    private fun save(devices: List<PairedDevice>) {
        file.parentFile.mkdirs()
        val temp = Files.createTempFile(file.parentFile.toPath(), file.name, ".tmp")
        try {
            // Android has no FileStore; its app files are private to the app anyway.
            try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) } catch (_: UnsupportedOperationException) {}
            Files.write(temp, json.encodeToString(DevicesFile.serializer(), DevicesFile(devices)).toByteArray())
            Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
    }
    private fun randomToken() = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
