package kinuvo.providers.apple

import ai.koog.prompt.streaming.StreamFrame
import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.AgentConfig
import kinuvo.testing.harness.request
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import kinuvo.protocol.ProtocolJson
import java.io.File
import kotlin.test.*

class FmLanguageModelTest {
    @Test fun emptyToolsKeepOnlyReplyInSchema() {
        val command = fmSchemaCommand(emptyList())
        assertEquals(1, command.count { it == "--schema" })
        assertContains(command.last(), "reply")
    }

    @Test
    fun unacceptedLicenseOffersSetupWithoutInvokingTheModel() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val native = FmLanguageModel { args ->
            calls += args
            FmResult(0, "Not agreed. Run 'sudo fm license' to review and agree.")
        }
        val provider = AppleProvider(native, Dispatchers.IO)
        try {
            assertEquals(listOf(SYSTEM), provider.models().map { it.id })
            val state = provider.availability(SYSTEM)
            assertFalse(state.actionable)
            assertEquals("license_required", state.reasonCode)
            assertContains(state.reason.orEmpty(), "sudo fm license")
            assertContains(state.reason.orEmpty(), "every user")
            assertFailsWith<IllegalStateException> {
                provider.model(AgentConfig(provider = "apple", model = SYSTEM)).request("", emptyList(), emptyList()).toList()
            }
            assertTrue(calls.all { it == listOf("license", "--status") })
        } finally { provider.close() }
    }

    @Test
    fun readinessIsRecheckedAfterSetup() = runBlocking {
        var licensed = false
        var ready = false
        val native = FmLanguageModel { args -> when (args.first()) {
            "license" -> FmResult(if (licensed) 0 else 69, if (licensed) "Agreed." else "Not agreed.")
            else -> FmResult(if (ready) 0 else 1, "", "model not ready")
        } }
        val provider = AppleProvider(native, Dispatchers.IO)
        try {
            assertEquals("license_required", provider.availability(SYSTEM).reasonCode)
            licensed = true
            val state = provider.availability(SYSTEM)
            assertEquals("model_unavailable", state.reasonCode)
            assertContains(state.reason.orEmpty(), "System Settings")
            ready = true
            assertTrue(provider.availability(SYSTEM).actionable)
            assertFalse(provider.availability(PRIVATE_CLOUD).actionable)
        } finally { provider.close() }
    }

    @Test
    fun textIsPassedAsAnArgumentAndReturnedAsAReply() = runBlocking {
        var response: List<String>? = null
        val native = FmLanguageModel { args ->
            if (args.first() == "respond") { response = args; FmResult(0, "Hello!\n") }
            else FmResult(0, "Agreed.")
        }
        val provider = AppleProvider(native, Dispatchers.IO)
        try {
            val frames = provider.model(AgentConfig(provider = "apple", model = SYSTEM))
                .request("Full instructions", listOf(kinuvo.protocol.session.SessionEntry.Context("Be helpful")), emptyList()).toList()
            assertEquals("Hello!", frames.filterIsInstance<StreamFrame.TextComplete>().single().text)
            val args = assertNotNull(response)
            assertEquals("Be helpful", args[args.indexOf("--instructions") + 1])
            assertTrue("--no-stream" in args)
            assertTrue("--schema" !in args)
        } finally { provider.close() }
    }

    @Test
    fun toolRequestsUseTheCliSchemaAndReachTheHarness() = runBlocking {
        var schemaRequested = false
        val native = FmLanguageModel { args -> when (args.first()) {
            "schema" -> { schemaRequested = true; FmResult(0, "{\"type\":\"object\"}") }
            "respond" -> {
                assertEquals("{\"type\":\"object\"}", args[args.indexOf("--schema") + 1])
                assertFalse(args[args.indexOf("--instructions") + 1].contains("Available tools:"))
                FmResult(0, """{"tool":"memory","content":"tea"}""")
            }
            else -> FmResult(0, "Agreed.")
        } }
        val provider = AppleProvider(native, Dispatchers.IO)
        try {
            val frames = provider.model(AgentConfig(provider = "apple", model = SYSTEM))
                .request("", emptyList(), listOf(ToolDescriptor("memory", "Remember a fact"))).toList()
            assertTrue(schemaRequested)
            assertEquals("memory", frames.filterIsInstance<StreamFrame.ToolCallComplete>().single().name)
            assertTrue(frames.none { it is StreamFrame.TextDelta })
        } finally { provider.close() }
    }

    @Test
    fun toolSchemaConstrainsTheToolAndItsTypedArgumentFields() {
        val tools = listOf(AppleTool("memory", "Memory", listOf(
            AppleParameter("action", "Action", "string", true, values = listOf("add", "remove")),
            AppleParameter("tags", "Tags", "array", false, items = "string"))))
        val args = fmSchemaCommand(tools)
        assertEquals(listOf("schema", "object", "--name", "Turn", "--anyOf"), args.take(5))
        val schema = ProtocolJson.parseToJsonElement(args.last()).jsonObject
        val properties = schema.getValue("properties").jsonObject
        assertEquals("memory", properties.getValue("tool").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content)
        assertEquals(listOf("add", "remove"), properties.getValue("action").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("string", properties.getValue("tags").jsonObject.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("tool", "action"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("arguments" in properties)
    }

    @Test
    fun subprocessCapturesBothStreamsAndNonzeroStatus() = runBlocking {
        val result = fmCommand(listOf("-c", "printf output; printf error >&2; exit 7"), "/bin/sh")
        assertEquals(FmResult(7, "output", "error"), result)
    }

    @Test
    fun cancellationAndTimeoutStopTheSubprocess() = runBlocking {
        val started = File.createTempFile("kinuvo-fm-", ".pid")
        started.delete()
        try {
            val job = async { fmCommand(listOf("-c", "echo $$ > '${started.path}'; exec sleep 30"), "/bin/sh") }
            withTimeout(5000) { while (!started.exists() || started.readText().isBlank()) delay(20) }
            val process = ProcessHandle.of(started.readText().trim().toLong()).get()
            job.cancelAndJoin()
            withTimeout(5000) { while (process.isAlive) delay(20) }
            assertFailsWith<IllegalStateException> {
                fmCommand(listOf("-c", "exec sleep 30"), "/bin/sh", timeoutSeconds = 0)
            }
            Unit
        } finally { started.delete() }
    }
}
