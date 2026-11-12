package kinuvo.protocol

import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.connection.ConnectParams
import kinuvo.protocol.device.DeviceToolKind
import kinuvo.protocol.device.Platform
import kotlin.test.*
import kotlinx.serialization.SerializationException

class ProtocolVersionTest {
    @Test fun peersSpeakTheNewestVersionBothSpeak() {
        assertEquals(ProtocolVersion.Current, ProtocolVersion.negotiate(ProtocolVersion.Current))
        assertEquals(ProtocolVersion.Current, ProtocolVersion.negotiate(ProtocolVersion.Current + 3, ProtocolVersion.Current))
        assertNull(ProtocolVersion.negotiate(ProtocolVersion.Current + 1))
        assertNull(ProtocolVersion.negotiate(ProtocolVersion.Minimum - 1))
    }

    @Test fun connectParamsNeedAVersion() {
        assertFailsWith<SerializationException> {
            ProtocolJson.decodeFromString(ConnectParams.serializer(), """{"device":"old"}""")
        }
        val params = ConnectParams("current", minProtocolVersion = ProtocolVersion.Minimum)
        assertEquals(params, ProtocolJson.decodeFromString(ConnectParams.serializer(), ProtocolJson.encodeToString(ConnectParams.serializer(), params)))
    }

    @Test fun aNewerPeersEnumValuesDoNotFailTheHandshake() {
        val params = ProtocolJson.decodeFromString(ConnectParams.serializer(),
            """{"device":"new","protocolVersion":${ProtocolVersion.Current},"deviceTools":["Contacts","Future"]}""")
        assertEquals(setOf(DeviceToolKind.Contacts), params.deviceTools)
        val capabilities = ProtocolJson.decodeFromString(ServerCapabilities.serializer(),
            """{"serverId":"s","features":["Browser","Future"],"platform":"Future"}""")
        assertEquals(setOf(ServerFeature.Browser), capabilities.features)
        assertEquals(Platform.Unknown, capabilities.platform)
    }
}
