package kinuvo.protocol.connection

import kinuvo.protocol.ProtocolVersion
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.device.DeviceToolKind
import kinuvo.protocol.device.DeviceToolKinds
import kinuvo.protocol.usage.Usage
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/**
 * Exactly one of [token] or [pairingCode] is set. [timeZone] is the Device's IANA zone. The Device speaks
 * [minProtocolVersion] through [protocolVersion]; without [minProtocolVersion], only [protocolVersion].
 */
@Serializable
data class ConnectParams(val device: String, val token: String? = null, val pairingCode: String? = null, val timeZone: String? = null,
    @Serializable(with = DeviceToolKinds::class) val deviceTools: Set<DeviceToolKind> = emptySet(),
    @Required val protocolVersion: Int = ProtocolVersion.Current, val minProtocolVersion: Int? = null)

/** [protocolVersion] is the version the connection speaks. */
@Serializable
data class ConnectResult(val token: String, val serverName: String, val usage: Usage? = null, val capabilities: ServerCapabilities, val management: Boolean, @Required val protocolVersion: Int = ProtocolVersion.Current, val deviceId: String? = null)

/** What a Server sends in a protocol mismatch's data: the versions it speaks. */
@Serializable
data class ProtocolRange(val minimum: Int, val current: Int)

/** [active] is false while the app is in the background, so the Server notifies instead of counting it as watching. */
@Serializable
data class PresenceParams(val active: Boolean)
