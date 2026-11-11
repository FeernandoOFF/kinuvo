package kinuvo.providers.apple

import kinuvo.harness.builtin.tools.toSpec
import kinuvo.harness.builtin.ANSWER_NUDGE
import kinuvo.testing.harness.*
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.AgentConfig
import kinuvo.protocol.model.ModelTier
import kinuvo.protocol.tool.ToolNames
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.plugin.ToolIdentity
import kinuvo.protocol.plugin.PluginInstanceId
import kinuvo.protocol.session.ToolCallId
import kinuvo.server.plugin.modelAlias
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kinuvo.harness.ModelProfile

@OptIn(ExperimentalCoroutinesApi::class)
class AppleProviderTest {
    @Test fun sharedAnswerNudgeReplacesAppleTrailingNudge() {
        val history = listOf(
            Message.User("Who?", RequestMetaInfo.Empty),
            Message.System(ANSWER_NUDGE, RequestMetaInfo.Empty),
        )
        assertFalse(promptFor(history).contains("Answer the user's message using the tool results above"))
        assertTrue(promptFor(history).trimEnd().endsWith(ANSWER_NUDGE))
        assertFalse(appleInstructions(history).contains(ANSWER_NUDGE))
        assertTrue(appleOffer(emptyList()).isEmpty())
    }

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
        var prompt: String? = null
        override fun generate(requestId: String, model: String, instructions: String, prompt: String, toolsJson: String,
            onText: (String) -> Unit, completion: (String?, String?) -> Unit) { this.instructions = instructions; this.prompt = prompt; tools = toolsJson; respond(onText, completion) }
        override fun cancel(requestId: String) { cancelled = requestId }
    }

    @Test
    fun instructionsUseOnlyTheLatestContextAndActivations() = runTest {
        val native = Native { _, done -> done("""{"tool":"reply","reply":"ok"}""", null) }
        model(native).request("stable", listOf(SessionEntry.Context("old context"),
            SessionEntry.Activation("skill", "old instructions"), SessionEntry.Context("latest context"),
            SessionEntry.Activation("skill", "latest instructions")), emptyList()).toList()
        assertEquals("latest context\n\nSkill 'skill' is active in this conversation:\n\nlatest instructions", native.instructions)
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
    fun acceptsOnlyToolsItsSchemaFits() {
        val text = ToolParameterDescriptor("query", "Query", ToolParameterType.String)
        val nested = ToolParameterDescriptor("filter", "Filter", ToolParameterType.Object(listOf(text)))
        val memory = ToolDescriptor(ToolNames.MEMORY, "Memory", listOf(ToolParameterDescriptor("action", "Action", ToolParameterType.Enum(arrayOf("add", "remove")))))
        val provider = AppleProvider(Native())
        assertTrue(provider.accepts(SYSTEM, memory.toSpec()))
        assertTrue(provider.accepts(SYSTEM, ToolDescriptor("optional", "Optional", optionalParameters = listOf(nested)).toSpec()))
        assertFalse(provider.accepts(SYSTEM, ToolDescriptor("nested", "Nested", listOf(nested)).toSpec()))
        val native = Native { _, done -> done("""{"tool":"reply","reply":""}""", null) }
        runBlocking { model(native).request("", emptyList(), listOf(memory)).toList() }
        assertTrue(native.tools!!.contains("""["add","remove"]"""))
    }

    @Test
    fun declaresTiersAndToolCaps() = runTest {
        val provider = AppleProvider(Native(cloud = 32768))
        assertEquals(mapOf(SYSTEM to ModelTier.Micro, PRIVATE_CLOUD to ModelTier.Small), provider.models().associate { it.id to it.tier })
        assertEquals(5, provider.profile(SYSTEM, ModelProfile.of(ModelTier.Micro)).tools.cap)
        assertEquals(16, provider.profile(PRIVATE_CLOUD, ModelProfile.of(ModelTier.Small)).tools.cap)
        assertNull(provider.profile(PRIVATE_CLOUD, ModelProfile.of(ModelTier.Frontier)).tools.cap)
    }

    @Test
    fun shortModelNamesRouteBackToTheOriginalToolIdentity() = runTest {
        val original = ToolIdentity(PluginInstanceId("kinuvo.browser"), "browser_open").modelAlias()
        lateinit var native: Native
        native = Native { _, done ->
            val offered = Json.parseToJsonElement(native.tools!!).jsonArray.single().jsonObject
            val name = offered.getValue("name").jsonPrimitive.content
            assertTrue(name.length < original.length)
            done("""{"tool":"$name","text":"about:blank"}""", null)
        }
        val frames = model(native).request("", emptyList(), listOf(ToolDescriptor(original, "Open Browser"))).toList()
        assertEquals(original, frames.filterIsInstance<StreamFrame.ToolCallComplete>().single().name)
    }

    @Test
    fun historicalCallsAndResultsUseTheCurrentShortSchemaName() = runTest {
        val original = ToolIdentity(PluginInstanceId("kinuvo.browser"), "browser_open").modelAlias()
        val native = Native { _, done -> done("""{"tool":"reply","reply":"Opened."}""", null) }
        model(native).request("", listOf(SessionEntry.UserMessage("Open Browser"),
            SessionEntry.ToolCall(ToolCallId("call"), original, "{}"), SessionEntry.ToolResult(ToolCallId("call"), original, "Opened")),
            listOf(ToolDescriptor(original, "Open Browser"))).toList()
        val short = appleOffer(listOf(ToolDescriptor(original, "Open Browser"))).single().name
        assertContains(native.prompt.orEmpty(), "Agent requested $short:")
        assertContains(native.prompt.orEmpty(), "Tool $short result:")
        assertFalse(original in native.prompt.orEmpty())
    }

    @Test
    fun nativePromptIncludesUserAndToolMessages() {
        val configured = model(Native())
        val messages = kinuvo.harness.builtin.SessionMessages(configured).prompt("stable", listOf(
            SessionEntry.Context("Current facts"), SessionEntry.UserMessage("one"),
            SessionEntry.ToolCall(ToolCallId("c"), ToolNames.MEMORY, "{}"), SessionEntry.ToolResult(ToolCallId("c"), ToolNames.MEMORY, "OK"))).messages
        val prompt = promptFor(messages)
        assertTrue(prompt.contains("User: one"))
        assertTrue(prompt.contains("Agent requested memory"))
        assertTrue(prompt.contains("Tool memory result: OK"))
    }

    @Test
    fun nativePromptCarriesOnlyTheLatestTurn() {
        val configured = model(Native())
        val messages = kinuvo.harness.builtin.SessionMessages(configured).prompt("stable", listOf(
            SessionEntry.UserMessage("what do you know about me?"), SessionEntry.AssistantMessage("I don't know anything about you."),
            SessionEntry.UserMessage("remember my name"), SessionEntry.ToolCall(ToolCallId("c"), ToolNames.MEMORY, "{}"),
            SessionEntry.ToolResult(ToolCallId("c"), ToolNames.MEMORY, "OK"))).messages
        val prompt = promptFor(messages)
        assertFalse("I don't know anything" in prompt, "Earlier replies are copied verbatim by the model")
        assertFalse("what do you know" in prompt)
        assertContains(prompt, "User: remember my name")
        assertContains(prompt, "Tool memory result: OK")
    }
}
