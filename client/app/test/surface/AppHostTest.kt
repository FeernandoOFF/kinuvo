package kinuvo.app.surface

import kinuvo.client.KinuvoException
import kotlinx.serialization.json.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AppHostTest {
    private val messages = mutableListOf<String>()
    private val links = mutableListOf<String>()
    private val host = AppHost(buildJsonObject { put("value", 42) }, JsonObject(emptyMap()), messages::add, links::add,
        toolResult = buildJsonObject { put("content", JsonArray(emptyList())) })
    private fun request(method: String, params: String = "{}", id: String = "1") = host.receive("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
    private fun notify(method: String, params: String = "{}") = host.receive("""{"jsonrpc":"2.0","method":"$method","params":$params}""")
    private fun initialize() {
        request("ui/initialize", """{"protocolVersion":"2026-01-26","appCapabilities":{}}""")
        notify("ui/notifications/initialized")
    }

    @Test fun handshakeWaitsForInitializedAndReplaysOnReconnect() {
        val response = request("ui/initialize", """{"protocolVersion":"2026-01-26"}""")
        assertEquals(1, response.size)
        assertFalse(host.state.value.ready)
        val notifications = notify("ui/notifications/initialized")
        assertEquals(listOf("ui/notifications/tool-input", "ui/notifications/tool-result"), notifications.map { Json.parseToJsonElement(it).jsonObject["method"]!!.jsonPrimitive.content })
        assertEquals(42, Json.parseToJsonElement(notifications[0]).jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        assertTrue(notify("ui/notifications/initialized").isEmpty())
        host.reconnect()
        initialize()
        assertTrue(host.state.value.ready)
    }

    @Test fun deliversResultsThatArriveAfterInitialization() {
        val host = AppHost(JsonObject(emptyMap()), JsonObject(emptyMap()), {}, {})
        host.receive("""{"jsonrpc":"2.0","id":1,"method":"ui/initialize","params":{"protocolVersion":"2026-01-26"}}""")
        assertEquals(1, host.receive("""{"jsonrpc":"2.0","method":"ui/notifications/initialized"}""").size)
        val result = buildJsonObject { put("content", JsonArray(emptyList())) }
        assertTrue(host.updateToolResult(result).single().contains("ui/notifications/tool-result"))
        assertTrue(host.updateToolResult(result).isEmpty())
    }

    @Test fun messagesLinksAndToolCallsAreGuarded() {
        initialize()
        request("ui/message", """{"role":"user","content":{"type":"text","text":"Hello"}}""")
        assertEquals(listOf("Hello"), messages)
        request("ui/message", """{"role":"assistant","content":{"type":"text","text":"Spoof"}}""")
        assertEquals(1, messages.size)
        request("ui/open-link", """{"url":"javascript:alert(1)"}""")
        assertTrue(links.isEmpty())
        request("ui/open-link", """{"url":"https://example.org"}""")
        assertEquals(listOf("https://example.org"), links)
        assertTrue(request("tools/call").single().contains("not available"))
        assertTrue(request("unknown").single().contains("-32601"))
        assertTrue(notify("unknown").isEmpty())
    }

    @Test fun mcpAppToolCallsAreRoutedAndAnsweredLater() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val delivered = mutableListOf<String>()
        val host = AppHost(JsonObject(emptyMap()), JsonObject(emptyMap()), {}, {}, { name, args ->
            calls += name to args
            if (name == "fail") error("Needs an Approval")
            if (name == "invalid") throw KinuvoException.InvalidParams("Bad arguments")
            buildJsonObject { put("content", JsonArray(emptyList())); put("structuredContent", buildJsonObject { put("ok", true) }) }
        }, this)
        host.deliver = { delivered += it }
        val init = host.receive("""{"jsonrpc":"2.0","id":1,"method":"ui/initialize","params":{"protocolVersion":"2026-01-26"}}""").single()
        assertTrue("serverTools" in init)
        host.receive("""{"jsonrpc":"2.0","method":"ui/notifications/initialized"}""")
        assertTrue(host.receive("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"refresh","arguments":{"q":1}}}""").isEmpty())
        assertTrue(host.receive("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"fail"}}""").isEmpty())
        assertTrue(host.receive("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"arguments":{}}}""").single().contains("-32602"))
        assertTrue(host.receive("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"invalid"}}""").isEmpty())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("refresh", "fail", "invalid"), calls.map { it.first })
        assertEquals(1, calls[0].second["q"]!!.jsonPrimitive.int)
        val ok = Json.parseToJsonElement(delivered[0]).jsonObject
        assertEquals(2, ok["id"]!!.jsonPrimitive.int)
        assertTrue(ok["result"]!!.jsonObject["structuredContent"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
        assertTrue(delivered[1].contains("Needs an Approval") && delivered[1].contains("-32000"))
        assertTrue(delivered[2].contains("Bad arguments") && delivered[2].contains("-32602"))
    }

    @Test fun staleToolRepliesCannotReachARecreatedApp() = runTest {
        val delivered = mutableListOf<String>()
        val host = AppHost(JsonObject(emptyMap()), JsonObject(emptyMap()), {}, {}, { _, _ -> JsonObject(emptyMap()) }, this)
        host.deliver = { delivered += it }
        host.receive("""{"jsonrpc":"2.0","id":1,"method":"ui/initialize","params":{"protocolVersion":"2026-01-26"}}""")
        host.receive("""{"jsonrpc":"2.0","method":"ui/notifications/initialized"}""")
        host.receive("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"refresh"}}""")
        host.reconnect()
        testScheduler.advanceUntilIdle()
        assertTrue(delivered.isEmpty())
        assertTrue(host.receive("""{"jsonrpc":"2.0","id":3,"method":"resources/read"}""").single().contains("not supported"))
        assertTrue(host.receive("""{"jsonrpc":"2.0","id":4,"method":"ui/update-model-context"}""").single().contains("not supported"))
    }

    @Test fun jsonRpcIdsAndErrors() {
        val response = request("ping", id = "\"request\"").single()
        assertEquals(JsonPrimitive("request"), Json.parseToJsonElement(response).jsonObject["id"])
        assertTrue(host.receive("[]").single().contains("-32600"))
        assertTrue(request("unknown").single().contains("-32601"))
        assertTrue(request("ping", "[]").single().contains("-32602"))
        assertTrue(host.receive("""{"jsonrpc":"2.0","id":1,"result":{}}""").isEmpty())
    }

    @Test fun respectsDeclaredDisplayModes() {
        request("ui/initialize", """{"protocolVersion":"2026-01-26","appCapabilities":{"availableDisplayModes":["inline"]}}""")
        notify("ui/notifications/initialized")
        request("ui/request-display-mode", """{"mode":"fullscreen"}""")
        assertEquals("inline", host.state.value.displayMode)
        assertTrue(host.displayMode("fullscreen").isEmpty())
    }

    @Test fun heightAndModesAreBounded() {
        initialize()
        notify("ui/notifications/size-changed", """{"height":100000}""")
        assertEquals(560, host.state.value.height)
        notify("ui/notifications/size-changed", """{"height":-4}""")
        assertEquals(560, host.state.value.height)
        request("ui/request-display-mode", """{"mode":"fullscreen"}""")
        assertEquals("fullscreen", host.state.value.displayMode)
        request("ui/request-display-mode", """{"mode":"pip"}""")
        assertEquals("fullscreen", host.state.value.displayMode)
        assertTrue(request("ping").single().contains("result"))
        assertTrue(host.receive("{").single().contains("-32700"))
    }
}
