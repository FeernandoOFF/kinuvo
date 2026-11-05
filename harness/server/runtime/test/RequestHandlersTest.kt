package kinuvo.harness.server

import kinuvo.protocol.*
import kinuvo.harness.server.devices.PeerRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RequestHandlersTest {
    @Test fun serverRegistersEveryMethod() = runTest {
        val server = ServerRuntime(RuntimeFixture().runtime(), object : PeerRegistry {
            override fun authenticate(device: String, token: String?, pairingCode: String?) = "token"
            override fun management(token: String) = true
            override fun toolDevices() = emptyList<ToolDevice>()
            override fun setTools(token: String, tools: Set<DeviceToolKind>) = "device"
        })
        server.close()
    }

    @Test fun duplicatesAndWrongDirectionFail() {
        val handlers = RequestHandlers<Unit>()
        handlers.on(Api.Presence) { }
        assertFailsWith<IllegalArgumentException> { handlers.on(Api.Presence) { } }
        assertFailsWith<IllegalArgumentException> { handlers.on(Api.DeviceRead) { DeviceToolResult(DeviceToolStatus.Unsupported) } }
        assertFailsWith<IllegalArgumentException> { handlers.requireComplete() }
        assertFailsWith<IllegalArgumentException> { handlers.requireComplete(listOf(Api.Presence)) }
        assertFailsWith<IllegalArgumentException> { handlers.requireComplete(listOf(Api.DeviceRead)) }
    }

    @Test fun dispatchDecodesAndMapsErrors() = runTest {
        val handlers = RequestHandlers<Unit>()
        handlers.on(Api.Presence) { check(it.active) { "inactive" } }
        val request = Frame.Request("1", Api.Presence.name, ProtocolJson.parseToJsonElement("""{"active":true}"""))
        assertTrue(handlers.handle(request, Unit).ok)
        assertFalse(handlers.handle(request.copy(params = ProtocolJson.parseToJsonElement("{}")), Unit).ok)
        assertFalse(handlers.handle(request.copy(method = "unknown"), Unit).ok)
    }
}
