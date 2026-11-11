package kinuvo.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class WireErrorTest {
    @Test fun responsesCarryTheCodeMessageAndData() {
        val error = WireError(WireErrorCode.Conflict, "Connection has live Sessions", JsonPrimitive("main"))
        val response = Frame.Response("1", ok = false, error = error)
        assertEquals(response, ProtocolJson.decodeFromString(Frame.serializer(), ProtocolJson.encodeToString(Frame.serializer(), response)))
    }

    @Test fun anOlderPeersPlainStringStillReadsAsAnError() {
        val mismatch = ProtocolJson.decodeFromString(Frame.serializer(), """{"type":"res","id":"1","ok":false,"error":"${ProtocolVersion.Mismatch}"}""")
        assertEquals(WireError(WireErrorCode.ProtocolMismatch, ProtocolVersion.Mismatch), (mismatch as Frame.Response).error)
        val other = ProtocolJson.decodeFromString(Frame.serializer(), """{"type":"res","id":"1","ok":false,"error":"No agent"}""")
        assertEquals(WireError(WireErrorCode.Internal, "No agent"), (other as Frame.Response).error)
    }
}
