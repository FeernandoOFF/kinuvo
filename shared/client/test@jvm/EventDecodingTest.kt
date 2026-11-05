package kinuvo.client

import kinuvo.protocol.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class EventDecodingTest {
    @Test fun malformedPayloadDoesNotEndCollection() = runTest {
        val valid = ProtocolJson.encodeToJsonElement(Api.DevicesChanged.payload, Unit)
        val events = flowOf(
            Frame.Event(Api.DevicesChanged.name, ProtocolJson.parseToJsonElement("42"), 1),
            Frame.Event("unrelated", valid, 2),
            Frame.Event(Api.DevicesChanged.name, valid, 3),
        )
        assertEquals(listOf(Unit), events.decodeEvents(Api.DevicesChanged).toList())
    }

    @Test fun reverseMethodsCannotBeCalled() = runTest {
        val client = KinuvoClient("local:test", "test")
        try {
            assertFailsWith<IllegalArgumentException> { client.call(Api.DeviceRead, DeviceToolRequest(DeviceToolKind.Contacts)) }
        } finally { client.close() }
    }
}
