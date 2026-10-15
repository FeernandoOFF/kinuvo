package kinuvo.harness.server

import kinuvo.protocol.*
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
    val id: String, val management: Boolean, val expiresAt: String)

@Serializable
private data class DevicesFile(val devices: List<PairedDevice> = emptyList())

class DeviceAuth(private val file: File, private val now: () -> Instant = { Clock.System.now() }) : PeerRegistry {
    private val random = SecureRandom()
    private val json = Json { prettyPrint = true }
    private val sessionTokens = mutableSetOf<String>()
    /** The app's own connections to the Server it embeds, which share their Device tools like paired Devices. */
    private val sessionDevices = mutableMapOf<String, ToolDevice>()
    var onNewCode: ((String) -> Unit)? = null
    var port = 0
    var addresses: () -> List<String> = { emptyList() }
    var publicUrl: String? = null
    @Volatile override var pairable = true
    @Volatile var pairingCode = ""; private set
    private var codeExpires = Instant.DISTANT_PAST
    private var codeManagement = false
    private var attempts = 0

    @Synchronized
    override fun authenticate(device: String, token: String?, pairingCode: String?): String? {
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
        save(devices() + PairedDevice(device.take(80), sha256(newToken), time.toString(), lastSeen = time.toString(),
            id = identity(sha256(newToken)), management = codeManagement, expiresAt = (time + 90.days).toString()))
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

    @Synchronized
    override fun pairing(refresh: Boolean, management: Boolean): PairingCode? {
        if (!pairable) return null
        if (refresh || pairingCode.isEmpty() || now() >= codeExpires || codeManagement != management) refreshCode(management)
        return PairingCode(pairingCode, codeExpires.toString(), port, addresses(), publicUrl, codeManagement)
    }

    @Synchronized
    override fun paired(token: String): List<PairedDeviceInfo> {
        val current = device(token)?.id
        return devices().map { PairedDeviceInfo(it.id, it.name, it.pairedAt, it.lastSeen, current = it.id == current,
            tools = it.tools, management = it.management, expiresAt = it.expiresAt) }
    }

    @Synchronized
    override fun revoke(id: String): Boolean {
        val devices = devices()
        val kept = devices.filterNot { it.id == id }
        if (kept.size == devices.size) return false
        save(kept)
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
