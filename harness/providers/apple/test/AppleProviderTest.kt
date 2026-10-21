package kinuvo.harness.apple

import kinuvo.harness.testing.*
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.tools.ToolNames
import kinuvo.protocol.SessionEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppleProviderTest {
    @BeforeTest
    fun main() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest
    fun cleanup() { Dispatchers.resetMain() }

    private class Native(private val unavailable: String? = null, private val cloud: Int = 0, private val respond: ((String) -> Unit, (String?, String?) -> Unit) -> Unit = { _, _ -> }) : AppleLanguageModel {
        var cancelled: String? = null
        override fun unavailableReason(model: String) = unavailable
        override fun unavailableCode(model: String) = unavailable?.let { "apple_intelligence_not_enabled" }
        override fun contextSize(model: String, completion: (Int) -> Unit) = completion(if (model == SYSTEM) 8192 else cloud)
        var tools: String? = null
        var instructions: String? = null
        override fun generate(requestId: String, model: String, instructions: String, prompt: String, toolsJson: String,
            onText: (String) -> Unit, completion: (String?, String?) -> Unit) { this.instructions = instructions; tools = toolsJson; respond(onText, completion) }
        override fun cancel(requestId: String) { cancelled = requestId }
    }

    @Test
    fun instructionsUseOnlyTheLatestContextAndActivations() = runTest {
        val native = Native { _, done -> done("""{"tool":"reply","reply":"ok"}""", null) }
        model(native).request("stable", listOf(SessionEntry.Context("old context"),
            SessionEntry.Activation("skill", "old instructions"), SessionEntry.Context("latest context"),
            SessionEntry.Activation("skill", "latest instructions")), emptyList()).toList()
        assertEquals("stable\n\nlatest context\n\nSkill 'skill' is active in this conversation:\n\nlatest instructions", native.instructions)
    }

    @Test
    fun availabilityReportsWhyTheModelCannotRun() = runTest {
        assertTrue(AppleProvider(Native()).availability("system").actionable)
        val state = AppleProvider(Native("Enable Apple Intelligence")).availability("system")
        assertFalse(state.actionable)
        assertEquals("Enable Apple Intelligence", state.reason)
        assertEquals("apple_intelligence_not_enabled", state.reasonCode)
    }

    private fun model(native: Native) = AppleProvider(native).model(AgentConfig(provider = "apple", model = "system"))

    @Test
    fun convertsCumulativeSnapshotsIntoDeltas() = runTest {
        val native = Native { text, done ->
            text("Hello"); text("Hello world")
            done("""{"tool":"reply","reply":"Hello world!"}""", null)
        }
        val frames = model(native).request("", emptyList(), emptyList()).toList()
        assertEquals(listOf("Hello", " world", "!"), frames.filterIsInstance<StreamFrame.TextDelta>().map { it.text })
    }

    @Test
    fun propagatesAvailabilityErrorsAndCancelsNativeRequests() = runTest {
        val unavailable = Native { _, done -> done(null, "Enable Apple Intelligence") }
        val error = assertFailsWith<IllegalStateException> { model(unavailable).request("", emptyList(), emptyList()).toList() }
        assertEquals("Enable Apple Intelligence", error.message)
        val hanging = Native { text, _ -> text("partial") }
        assertEquals("partial", (model(hanging).request("", emptyList(), emptyList()).first() as StreamFrame.TextDelta).text)
        assertNotNull(hanging.cancelled)
    }

    @Test
    fun rejectsCallsToToolsNotOffered() = runTest {
        val native = Native { _, done -> done("""{"tool":"memory","action":"add","target":"user","content":"tea"}""", null) }
        assertFailsWith<IllegalStateException> { model(native).request("", emptyList(), emptyList()).toList() }
    }

    @Test
    fun memoryRequestsBecomeHarnessToolCallsWithoutPrematureSuccessText() = runTest {
        val native = Native { _, done -> done("""{"tool":"memory","action":"replace","target":"user","content":"Prefers tea","old_text":"coffee"}""", null) }
        val frames = model(native).request("", emptyList(), listOf(ToolDescriptor(ToolNames.MEMORY, "Memory"))).toList()
        assertTrue(frames.none { it is StreamFrame.TextDelta })
        val call = frames.filterIsInstance<StreamFrame.ToolCallComplete>().single()
        assertEquals(ToolNames.MEMORY, call.name)
        val args = Json.parseToJsonElement(call.content).jsonObject
        assertEquals("coffee", args.getValue("old_text").jsonPrimitive.content)
        assertEquals("replace", args.getValue("action").jsonPrimitive.content)
    }

    @Test
    fun offersPrivateCloudOnlyWhenTheBuildHasIt() = runTest {
        assertEquals(listOf(SYSTEM to 8192), AppleProvider(Native()).models().map { it.id to it.context })
        assertEquals(listOf(SYSTEM, PRIVATE_CLOUD), AppleProvider(Native(cloud = 32768)).models().map { it.id })
    }

    @Test
    fun offersFewSimpleToolsWithMemoryFirst() {
        val text = ToolParameterDescriptor("query", "Query", ToolParameterType.String)
        val nested = ToolParameterDescriptor("filter", "Filter", ToolParameterType.Object(listOf(text)))
        val many = (1..8).map { ToolDescriptor("mcp_$it", "MCP $it", listOf(text)) }
        val offered = appleTools(many + listOf(ToolDescriptor(ToolNames.SHELL, "Shell", listOf(text)), ToolDescriptor("nested", "Nested", listOf(nested)),
            ToolDescriptor(ToolNames.MEMORY, "Memory", listOf(ToolParameterDescriptor("action", "Action", ToolParameterType.Enum(arrayOf("add", "remove")))))))
        assertEquals(listOf(ToolNames.MEMORY) + (1..5).map { "mcp_$it" }, offered.map { it.name })
        val native = Native { _, done -> done("""{"tool":"reply","reply":""}""", null) }
        runBlocking { model(native).request("", emptyList(), offered).toList() }
        assertTrue(native.tools!!.contains("""["add","remove"]"""))
    }

    @Test
    fun nativePromptIncludesUserAndToolMessages() {
        val configured = model(Native())
        val messages = kinuvo.harness.core.SessionMessages(configured).prompt("stable", listOf(
            SessionEntry.Context("Current facts"), SessionEntry.UserMessage("one"),
            SessionEntry.ToolCall("c", ToolNames.MEMORY, "{}"), SessionEntry.ToolResult("c", ToolNames.MEMORY, "OK"))).messages
        val prompt = promptFor(messages)
        assertTrue(prompt.contains("User: one"))
        assertTrue(prompt.contains("Agent requested memory"))
        assertTrue(prompt.contains("Tool memory result: OK"))
    }
}
