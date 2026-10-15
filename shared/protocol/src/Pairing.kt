package kinuvo.protocol

import kotlinx.serialization.Serializable

@Serializable
data class PairingParams(val refresh: Boolean = false, val management: Boolean = false)

/** The Server's current pairing code; [expiresAt] is ISO-8601. [addresses] are the Server's LAN IPs, reachable on [port]. */
@Serializable
data class PairingCode(val code: String, val expiresAt: String, val port: Int, val addresses: List<String> = emptyList(), val publicUrl: String? = null, val management: Boolean = false)

/** A Device paired with the Server; [current] is the one asking. Times are ISO-8601. */
@Serializable
data class PairedDeviceInfo(
    val id: String,
    val name: String,
    val pairedAt: String,
    val lastSeen: String? = null,
    val connected: Boolean = false,
    val current: Boolean = false,
    val tools: Set<DeviceToolKind> = emptySet(),
    val management: Boolean = false,
    val expiresAt: String? = null,
)

@Serializable
data class DeviceRef(val id: String)

/** Where to pair: [server] is `host:port` or an http(s)/ws(s) URL. */
data class PairLink(val server: String, val code: String)
