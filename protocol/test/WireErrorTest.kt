package kinuvo.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test fun aForbiddenSaysWhyInItsData() {
        val management = WireError(WireErrorCode.Forbidden, "No", buildJsonObject { put("reason", WireErrorReason.Management) })
        assertEquals(WireErrorReason.Management, management.reason)
        assertNull(WireError(WireErrorCode.Forbidden, "No").reason)
        assertNull(WireError(WireErrorCode.Forbidden, "No", JsonPrimitive("main")).reason)
    }
}
