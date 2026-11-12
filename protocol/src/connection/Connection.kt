package kinuvo.protocol.connection

import kinuvo.protocol.ProtocolVersion
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.device.DeviceToolKind
import kinuvo.protocol.usage.Usage
import kotlinx.serialization.Serializable

/** Exactly one of [token] or [pairingCode] is set. [timeZone] is the Device's IANA zone. */
@Serializable
data class ConnectParams(val device: String, val token: String? = null, val pairingCode: String? = null, val timeZone: String? = null, val deviceTools: Set<DeviceToolKind> = emptySet(),
    @kotlinx.serialization.Required val protocolVersion: Int = ProtocolVersion.Current)

@Serializable
data class ConnectResult(val token: String, val serverName: String, val usage: Usage? = null, val capabilities: ServerCapabilities, val management: Boolean, @kotlinx.serialization.Required val protocolVersion: Int = ProtocolVersion.Current, val deviceId: String? = null)

/** [active] is false while the app is in the background, so the Server notifies instead of counting it as watching. */
@Serializable
data class PresenceParams(val active: Boolean)
