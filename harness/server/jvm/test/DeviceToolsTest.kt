package kinuvo.harness.server

import kinuvo.harness.testing.*

import kinuvo.harness.core.RunContext
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class DeviceToolsTest {
    private val home = Files.createTempDirectory("device-tools").toFile()
    private val harness = Harness(home = Path(home.path), modelFactory = { scriptedModel { _, _ -> kotlinx.coroutines.flow.emptyFlow() } })
    private val device = ToolDevice("phone", "iPhone", setOf(DeviceToolKind.Contacts))
    private val events = Channel<AgentEvent>(Channel.UNLIMITED)

    private suspend fun tool(): kinuvo.harness.core.tools.Tool {
        val agent = harness.store.agents().single()
        val session = harness.newSession(agent)
        harness.log.onEvent { _, _, event -> events.trySend(event) }
        harness.log.publish(agent.id, session.id, AgentEvent.RunStarted("run"))
        harness.devices.paired = { listOf(device) }
        return harness.devices.prepare(RunContext(agent, session.id), activate = true).tools.single { it.descriptor.name == "contacts_lookup" }
    }

    private suspend fun waiting(): AgentEvent.QuestionsAsked {
        while (true) { val event = events.receive(); if (event is AgentEvent.QuestionsAsked) return event }
    }

    @Test
    fun waitsUntilThePhoneOpensAndReturnsProvenance(): Unit = runBlocking {
        withTimeout(5_000) {
            val tool = tool()
            val result = async { tool.execute(buildJsonObject { put("query", "Ada") }, "call") }
            assertEquals("call", waiting().toolCallId)
            harness.devices.connect(device.id, device.tools, true) { DeviceToolResult(DeviceToolStatus.Ready, buildJsonObject { put("contacts", JsonArray(emptyList())) }) }
            val read = ProtocolJson.decodeFromString(DeviceToolResult.serializer(), result.await())
            assertEquals(DeviceToolStatus.Ready, read.status)
            assertEquals("phone", read.deviceId)
            assertEquals("iPhone", read.deviceName)
            assertNotNull(read.readAt)
        }
    }

    @Test
    fun skipAndCancellationClearTheWaitingForm() = runBlocking {
        withTimeout(5_000) {
            val tool = tool()
            val first = async { tool.execute(buildJsonObject { put("query", "Ada") }, "first") }
            val pending = waiting()
            assertTrue(harness.questions.respond(pending.questionsId, null))
            assertEquals(DeviceToolStatus.Skipped, ProtocolJson.decodeFromString(DeviceToolResult.serializer(), first.await()).status)
            assertFalse(harness.questions.respond(pending.questionsId, null))
            val second = async { tool.execute(buildJsonObject { put("query", "Ada") }, "second") }
            val stopped = waiting()
            second.cancelAndJoin()
            assertFalse(harness.questions.respond(stopped.questionsId, null))
        }
    }

    @Test
    fun toolDisabledWhileWaitingDoesNotRead() = runBlocking {
        withTimeout(5_000) {
            val tool = tool()
            val result = async { tool.execute(buildJsonObject { put("query", "Ada") }, "call") }
            waiting()
            harness.devices.paired = { listOf(device.copy(tools = emptySet())) }
            harness.devices.connect(device.id, emptySet(), true) { error("Must not read") }
            assertEquals(DeviceToolStatus.Denied, ProtocolJson.decodeFromString(DeviceToolResult.serializer(), result.await()).status)
        }
    }

    @Test
    fun backgroundDevicesWaitAndAmbiguousDevicesNeedAnExplicitChoice(): Unit = runBlocking {
        withTimeout(5_000) {
            val tool = tool()
            harness.devices.connect(device.id, device.tools, false) { error("Background read") }
            val result = async { tool.execute(buildJsonObject { put("query", "Ada") }, "call") }
            harness.questions.respond(waiting().questionsId, null)
            result.await()
            harness.devices.paired = { listOf(device, device.copy(id = "other")) }
            assertFailsWith<IllegalArgumentException> { tool.execute(buildJsonObject { put("query", "Ada") }, "call") }
        }
    }

    @Test
    fun checkAgainRetriesAStalledForegroundRead() = runBlocking {
        withTimeout(5_000) {
            val tool = tool()
            var reads = 0
            harness.devices.connect(device.id, device.tools, true) {
                reads++
                DeviceToolResult(if (reads == 1) DeviceToolStatus.Unavailable else DeviceToolStatus.Ready)
            }
            val result = async { tool.execute(buildJsonObject { put("query", "Ada") }, "call") }
            harness.questions.respond(waiting().questionsId, emptyMap())
            assertEquals(DeviceToolStatus.Ready, ProtocolJson.decodeFromString(DeviceToolResult.serializer(), result.await()).status)
            assertEquals(2, reads)
        }
    }

    @Test
    fun anOldSocketCannotRemoveTheNewConnection() = runBlocking {
        val tool = tool()
        val old: suspend (DeviceToolRequest) -> DeviceToolResult = { error("Old connection") }
        harness.devices.connect(device.id, device.tools, true, old)
        harness.devices.connect(device.id, device.tools, true) { DeviceToolResult(DeviceToolStatus.Ready) }
        harness.devices.disconnect(device.id, old)
        assertEquals(DeviceToolStatus.Ready, ProtocolJson.decodeFromString(DeviceToolResult.serializer(), tool.execute(buildJsonObject { put("query", "Ada") }, "call")).status)
    }

    @Test
    fun disconnectDuringANativeReadWaitsAndResumesOnTheNewSocket() = runBlocking {
        val server = HarnessServer(harness)
        val port = server.start("127.0.0.1", 0, schedule = false)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var token: String? = null
        val client = kinuvo.client.KinuvoClient("ws://127.0.0.1:$port/ws", "iPhone", pairingCode = server.auth.currentPairingCode(),
            onToken = { token = it }, deviceTools = { device.tools }, deviceRead = {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            })
        var replacement: kinuvo.client.KinuvoClient? = null
        try {
            withTimeout(10_000) {
                client.connect()
                val agent = harness.store.agents().single()
                val session = harness.newSession(agent)
                harness.log.onEvent { _, _, event -> events.trySend(event) }
                val tool = harness.devices.prepare(RunContext(agent, session.id), activate = true).tools.single { it.descriptor.name == "contacts_lookup" }
                val result = async { tool.execute(buildJsonObject { put("query", "Ada") }, "call") }
                started.await()
                client.close()
                cancelled.await()
                waiting()
                val reopened = kinuvo.client.KinuvoClient("ws://127.0.0.1:$port/ws", "renamed phone", token = token,
                    deviceTools = { device.tools }, deviceRead = { DeviceToolResult(DeviceToolStatus.Ready) })
                replacement = reopened
                reopened.connect()
                val read = ProtocolJson.decodeFromString(DeviceToolResult.serializer(), result.await())
                assertEquals(DeviceToolStatus.Ready, read.status)
                assertEquals("iPhone", read.deviceName)
            }
        } finally { client.close(); replacement?.close(); server.stop() }
    }

    @Test
    fun oldSocketPresenceAndToolsCannotReplaceTheReconnectedDevice() = runBlocking {
        val server = HarnessServer(harness)
        val port = server.start("127.0.0.1", 0, schedule = false)
        var token: String? = null
        val old = kinuvo.client.KinuvoClient("ws://127.0.0.1:$port/ws", "iPhone", pairingCode = server.auth.currentPairingCode(),
            onToken = { token = it }, deviceTools = { device.tools }, deviceRead = { error("Old socket must not read") })
        var replacement: kinuvo.client.KinuvoClient? = null
        try {
            withTimeout(10_000) {
                old.connect()
                val reopened = kinuvo.client.KinuvoClient("ws://127.0.0.1:$port/ws", "iPhone", token = token,
                    deviceTools = { device.tools }, deviceRead = { DeviceToolResult(DeviceToolStatus.Ready) })
                replacement = reopened
                reopened.connect()
                old.call(Api.Presence, PresenceParams(false))
                old.call(Api.DeviceTools, DeviceToolsParams(emptySet()))
                assertEquals(device.tools, server.auth.toolDevices().single().tools)
                assertTrue(harness.devices.devices().single().available)
                old.close()
                val agent = harness.store.agents().single()
                val session = harness.newSession(agent)
                val tool = harness.devices.prepare(RunContext(agent, session.id), activate = true).tools.single { it.descriptor.name == "contacts_lookup" }
                val result = tool.execute(buildJsonObject { put("query", "Ada") }, "call")
                assertEquals(DeviceToolStatus.Ready, ProtocolJson.decodeFromString(DeviceToolResult.serializer(), result).status)
            }
        } finally { old.close(); replacement?.close(); server.stop() }
    }

    @Test
    fun authenticatedClientAnswersReverseRequestsAndCanDisableTools() = runBlocking {
        val server = HarnessServer(harness)
        val port = server.start("127.0.0.1", 0, schedule = false)
        var enabled = setOf(DeviceToolKind.Contacts)
        var token: String? = null
        val client = kinuvo.client.KinuvoClient("ws://127.0.0.1:$port/ws", "iPhone", pairingCode = server.auth.currentPairingCode(),
            onToken = { token = it }, deviceTools = { enabled }, deviceRead = { DeviceToolResult(DeviceToolStatus.Ready, buildJsonObject { put("query", it.query) }) })
        try {
            withTimeout(10_000) {
                client.connect()
                val agent = harness.store.agents().single()
                val session = harness.newSession(agent)
                val tool = harness.devices.prepare(RunContext(agent, session.id), activate = true).tools.single { it.descriptor.name == "contacts_lookup" }
                val result = ProtocolJson.decodeFromString(DeviceToolResult.serializer(), tool.execute(buildJsonObject { put("query", "Ada") }, "call"))
                assertEquals("Ada", result.data.getValue("query").jsonPrimitive.content)
                val before = server.auth.toolDevices().single().id
                assertNotEquals(server.auth.devices().single().tokenSha256, before)
                enabled = emptySet()
                client.updateDeviceTools()
                assertEquals(emptySet(), server.auth.toolDevices().single().tools)
                assertEquals(DeviceToolStatus.Unavailable, ProtocolJson.decodeFromString(DeviceToolResult.serializer(), tool.execute(buildJsonObject { put("query", "Ada") }, "call")).status)
                // Supplied display names cannot replace the paired identity.
                assertNotNull(server.auth.authenticate("forged name", token, null))
                assertEquals(before, server.auth.toolDevices().single().id)
                assertEquals("iPhone", server.auth.toolDevices().single().name)
            }
        } finally { client.close(); server.stop() }
    }
}
