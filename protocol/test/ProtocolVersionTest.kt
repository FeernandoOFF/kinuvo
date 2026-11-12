package kinuvo.protocol

import kotlinx.serialization.SerializationException
import kotlin.test.*

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
