package kinuvo.protocol

import kotlin.test.*

class TypedProtocolTest {
    @Test fun idsRemainJsonStrings() {
        assertEquals("\"agent\"", ProtocolJson.encodeToString(AgentId.serializer(), AgentId("agent")))
        assertEquals("\"session\"", ProtocolJson.encodeToString(SessionId.serializer(), SessionId("session")))
        val json = """{"agentId":"agent","sessionId":"session","text":"hello","attachments":[],"voice":false}"""
        val params = ProtocolJson.decodeFromString(SendParams.serializer(), json)
        assertEquals(AgentId("agent"), params.agentId)
        assertEquals(SessionId("session"), params.sessionId)
        assertEquals(json, ProtocolJson.encodeToString(SendParams.serializer(), params))
    }

    @Test fun catalogHasUniqueNamesAndReverseDirection() {
        assertEquals(Api.methods.size, Api.methods.map { it.name }.toSet().size)
        assertEquals(setOf(Api.DeviceRead, Api.ComputerAct), Api.methods.filter { it.direction == MethodDirection.ToDevice }.toSet())
        assertFailsWith<IllegalArgumentException> { Api.DeviceRead.requireDirection(MethodDirection.ToServer) }
    }
}
