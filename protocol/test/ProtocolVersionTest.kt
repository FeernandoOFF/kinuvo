package kinuvo.protocol

import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.connection.ConnectParams
import kinuvo.protocol.device.DeviceToolKind
import kinuvo.protocol.device.Platform
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.session.UpdateSessionParams
import kotlin.test.*
import kotlinx.serialization.SerializationException

class ProtocolVersionTest {
    @Test fun sessionsWithoutInboxFieldsStillDecode() {
        val info = ProtocolJson.decodeFromString<SessionInfo>("""{"id":"session","agentId":"agent","title":"Chat","favorite":true}""")
        assertTrue(info.favorite)
        assertFalse(info.settled)
        assertFalse(info.unread)
        val update = ProtocolJson.decodeFromString<UpdateSessionParams>("""{"sessionId":"session","agentId":"agent","favorite":true}""")
        assertEquals(true, update.favorite)
        assertNull(update.settled)
        assertNull(update.seen)
        val details = assertIs<SessionEntry.Details>(ProtocolJson.decodeFromString<SessionEntry>("""{"type":"details","title":"Chat"}"""))
        assertFalse(details.settled)
        assertFalse(details.unread)
    }

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

    @Test fun connectParamsRegisterDeviceToolsByDefault() {
        val old = ProtocolJson.decodeFromString(ConnectParams.serializer(), """{"device":"old","protocolVersion":${ProtocolVersion.Current}}""")
        assertTrue(old.registerDeviceTools)
        val action = ConnectParams("action", registerDeviceTools = false)
        assertEquals(action, ProtocolJson.decodeFromString(ConnectParams.serializer(), ProtocolJson.encodeToString(ConnectParams.serializer(), action)))
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
