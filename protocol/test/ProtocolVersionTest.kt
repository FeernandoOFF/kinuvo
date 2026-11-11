package kinuvo.protocol

import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.connection.ConnectParams
import kotlin.test.*
import kotlinx.serialization.SerializationException

class ProtocolVersionTest {
    @Test fun browserRequiresExplicitMatchingProtocol() {
        ProtocolVersion.requireCompatible(ProtocolVersion.Current)
        ((0 until ProtocolVersion.Current) + (ProtocolVersion.Current + 1)).forEach { version ->
            assertEquals(ProtocolVersion.Mismatch, assertFailsWith<IllegalArgumentException> {
                ProtocolVersion.requireCompatible(version)
            }.message)
        }
        assertFailsWith<SerializationException> {
            ProtocolJson.decodeFromString(ConnectParams.serializer(), """{"device":"old"}""")
        }
        val params = ConnectParams("current")
        assertEquals(params, ProtocolJson.decodeFromString(ConnectParams.serializer(), ProtocolJson.encodeToString(ConnectParams.serializer(), params)))
        assertEquals(ServerFeature.Browser, ProtocolJson.decodeFromString(ServerFeature.serializer(), "\"Browser\""))
    }
}
