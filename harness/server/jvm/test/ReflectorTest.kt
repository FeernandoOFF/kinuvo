package kinuvo.harness.server

import kinuvo.harness.testing.*
import ai.koog.prompt.message.Message

import kinuvo.harness.core.modelAlias
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.core.PrivateFile
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.core.store.knowledgeConfig
import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.KnowledgeChangeRef
import kinuvo.protocol.KnowledgeChangesParams
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReflectorTest {
    private val home = Files.createTempDirectory("harness-reflector").toFile()
    private var reflections = 0
    private val instructionsSeen = mutableListOf<String>()
    private val toolDescriptions = mutableListOf<String>()
    private val model = scriptedModel { prompt, tools ->
        val instructions = prompt.messages.filterIsInstance<Message.System>().firstOrNull()?.textContent().orEmpty()
        val history = prompt.messages
        val review = history.filterIsInstance<Message.User>().lastOrNull { it.textContent().startsWith("Background Reflection instructions:") }?.textContent()
        instructionsSeen += review?.removePrefix("Background Reflection instructions:\n") ?: instructions
        tools.firstOrNull { it.name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias() }?.let { toolDescriptions += it.description }
        when {
            review == null -> flowOf(StreamFrame.TextDelta("Nice!"), StreamFrame.End())
            history.dropWhile { it !is Message.User || it.textContent() != review }.toolResults.isNotEmpty() -> flowOf(StreamFrame.TextDelta("Saved."), StreamFrame.End())
            else -> {
                reflections++
                flowOf(StreamFrame.ToolCallComplete("c$reflections", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias(), """{"action":"create","type":"person","title":"Ana","set":"{\"relationship\":\"sister\"}","reason":"Family","evidence":"${Regex("user-\\d+").find(review!!)?.value}"}"""), StreamFrame.End())
            }
        }
    }
    private val harness = Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))), modelFactory = { model })

    @Test
    fun knowledgeSettingsKeepKeysPrivate() {
        val s = kinuvo.protocol.KnowledgeSettings(provider = "anytype", options = mapOf("apiKey" to "secret-key", "space" to "space-1"))
        val saved = harness.saveKnowledgeSettings(s)
        assertEquals(kinuvo.protocol.KnowledgeSettings.SECRET, saved.settings.options["apiKey"])
        assertEquals("space-1", saved.settings.options["space"])
        harness.saveKnowledgeSettings(saved.settings.copy(options = saved.settings.options + ("space" to "space-2")))
        val yaml = File(home, "config.yaml").readText()
        assertTrue("secret-key" in yaml)
        assertTrue("space-2" in yaml)
        assertTrue(runCatching { harness.saveKnowledgeSettings(s.copy(options = emptyMap())) }.isFailure)
    }

    @Test
    fun testsKnowledgeSettingsWithoutSavingThem() = runBlocking {
        val folder = File(home, "vault").path
        val ok = harness.testKnowledge(kinuvo.protocol.KnowledgeSettings(folder))
        assertTrue(ok.error == null && "types" in ok.summary!!, "$ok")
        val bad = harness.testKnowledge(kinuvo.protocol.KnowledgeSettings(provider = "anytype", options = mapOf("space" to "s")))
        assertTrue("API key" in bad.error!!, "$bad")
        assertEquals(null, harness.files.knowledgeConfig().path)
    }

    @Test
    fun knowledgeSettingsShowSecretReferences() {
        val reference = "\${pass://Kinuvo/Anytype/api key}"
        val s = kinuvo.protocol.KnowledgeSettings(provider = "anytype", options = mapOf("apiKey" to reference, "space" to "space-1"))
        assertEquals(reference, harness.saveKnowledgeSettings(s).settings.options["apiKey"])
    }

    @Test
    fun conventionsArePerProviderAndReachAgentsAndReflection() = runBlocking {
        val agent = harness.store.agents().single()
        val session = harness.newSession(agent)
        val conventions = "People are person notes.\nAdd dated facts under ## Timeline."
        val saved = harness.saveKnowledgeSettings(kinuvo.protocol.KnowledgeSettings(conventions = mapOf("markdown" to conventions, "anytype" to "Humans are profile.")))
        assertEquals(conventions, saved.settings.conventions["markdown"])
        assertEquals(conventions + "\n", File(home, "conventions/markdown.md").readText())
        assertTrue(conventions !in harness.store.entries(agent, harness.newSession(agent).id).filterIsInstance<SessionEntry.Started>().single().instructions)
        harness.reflect(agent, "s1", listOf(SessionEntry.UserMessage("My sister Ana is visiting")))
        assertTrue(instructionsSeen.any { it.startsWith("You maintain") && conventions in it && "Humans are profile." !in it })
        harness.runner(agent, session.id).run(session.id, "Hello").collect {}
        assertTrue(conventions !in toolDescriptions.last())
        assertTrue(harness.store.entries(agent, session.id).filterIsInstance<SessionEntry.Context>().any { conventions in it.content })
    }

    @Test
    fun reflectionPromptIsSavedAppliedAndResetWithoutRestart() = runBlocking {
        val agent = harness.store.agents().single()
        val initial = harness.knowledgeSetup().settings
        assertEquals(null, initial.reflectionPrompt)
        val custom = "Save durable preferences in User Notes. Keep Memory concise."
        val settings = initial.copy(reflectionPrompt = custom, conventions = mapOf("markdown" to "People are person notes."))
        assertEquals(custom, harness.saveKnowledgeSettings(settings).settings.reflectionPrompt)
        assertEquals(custom, harness.files.knowledgeConfig().reflectionPrompt)
        harness.docs.write(agent.id, kinuvo.harness.core.AgentDoc.Memory, "- Use concrete examples\n")
        harness.reflect(agent, "s1", listOf(SessionEntry.UserMessage("I prefer concise replies")))
        val instructions = instructionsSeen.last()
        assertTrue(instructions.startsWith(custom))
        assertTrue("People are person notes." in instructions)
        assertTrue("Use concrete examples" in instructions)
        assertTrue("Current User Notes:" in instructions)
        assertTrue("Today is " in instructions)
        assertTrue("person" in instructions)

        val reset = harness.saveKnowledgeSettings(settings.copy(reflectionPrompt = kinuvo.protocol.DEFAULT_REFLECTION_PROMPT))
        assertEquals(null, reset.settings.reflectionPrompt)
        assertEquals(null, harness.files.knowledgeConfig().reflectionPrompt)
        harness.reflect(agent, "s2", listOf(SessionEntry.UserMessage("My sister Ana is visiting")))
        assertTrue(instructionsSeen.last().startsWith(kinuvo.protocol.DEFAULT_REFLECTION_PROMPT))
        assertEquals(null, harness.saveKnowledgeSettings(settings.copy(reflectionPrompt = "  ")).settings.reflectionPrompt)
    }

    @Test
    fun reflectsOnlyOnWhatsNew() = runBlocking {
        val agent = harness.store.agents().single()
        val reflector = Reflector(harness, CoroutineScope(Dispatchers.Default), busy = { false })
        val session = harness.newSession(agent)
        harness.runner(agent, session.id).run(session.id, "My sister Ana is visiting").collect {}

        assertEquals(0, reflector.reflect(agent, session.id))
        assertEquals(0, reflector.reflect(agent, session.id))
        assertEquals(1, reflections)
        val proposal = harness.store.entries(agent, session.id).filterIsInstance<SessionEntry.Reflection>().single().result.proposals.single()
        assertEquals(null, harness.knowledge.find("Ana"))
        ReflectionReview(harness).respond(agent, session.id, proposal.id, true)
        assertEquals("sister", harness.knowledge.find("Ana")!!.properties["relationship"])
        assertTrue(harness.store.entries(agent, session.id).filterIsInstance<SessionEntry.Context>().any { "## Knowledge" in it.content })
    }

    @Test
    fun persistsDraftsBeforeAdvancingReflectionProgress() = runBlocking {
        val agent = harness.store.agents().single()
        val session = harness.newSession(agent)
        harness.runner(agent, session.id).run(session.id, "My sister Ana is visiting").collect {}
        var delivered = false
        harness.log.subscribe(agent, session.id, null, 100, SessionLog.Subscriber { update ->
            val change = update.change as? kinuvo.protocol.SessionChange.Entry
            if (change?.entry is SessionEntry.Reflection) {
                assertTrue(!File(home, "reflections.json").exists())
                delivered = true
            }
            true
        })
        val reflector = Reflector(harness, CoroutineScope(Dispatchers.Default), busy = { false })
        reflector.reflect(agent, session.id)
        assertTrue(delivered)
        assertTrue(File(home, "reflections.json").exists())
        assertEquals(1, harness.store.entries(agent, session.id).filterIsInstance<SessionEntry.Reflection>().single().result.proposals.size)
    }

    @Test
    fun clientsReviewAndUndoChanges() = runBlocking {
        val server = HarnessServer(harness)
        val port = server.start("127.0.0.1", 0)
        try {
            withTimeout(30_000) {
                val client = KinuvoClient("ws://127.0.0.1:$port/ws", "desk", pairingCode = server.auth.currentPairingCode())
                client.connect()
                harness.knowledge.create("person", "Sam")
                val change = client.call(Api.KnowledgeChanges, KnowledgeChangesParams()).single()
                assertEquals("People/Sam", client.call(Api.KnowledgeNote, kinuvo.protocol.NoteRef("sam")).ref)
                assertTrue(client.call(Api.UndoKnowledgeChange, KnowledgeChangeRef(change.id)).undone)
                assertTrue(client.call(Api.KnowledgeSearch, kinuvo.protocol.KnowledgeSearchParams("Sam")).isEmpty())
                client.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun settingsAreValidatedAndApplied() = runBlocking {
        val folder = File(home, "vault/Kinuvo").path
        assertEquals(folder, harness.saveKnowledgeSettings(kinuvo.protocol.KnowledgeSettings(folder, reflection = false)).folder)
        harness.knowledge.create("person", "Sam")
        assertTrue(File(folder, "People/Sam.md").isFile)
        assertTrue(!harness.reflects(harness.store.agents().single()))
        assertTrue(runCatching { harness.saveKnowledgeSettings(kinuvo.protocol.KnowledgeSettings("relative/dir")) }.isFailure)
        val before = harness.knowledgeSetup()
        assertTrue(runCatching { harness.saveKnowledgeSettings(kinuvo.protocol.KnowledgeSettings(conventions = mapOf("unknown" to "Text"))) }.isFailure)
        assertEquals(before, harness.knowledgeSetup())

        val saved = harness.saveTasksSettings(kinuvo.protocol.TasksSettings(lists = mapOf("Inbox" to "default"), startBeforeDays = 7))
        assertEquals(7, saved.settings.startBeforeDays)
        assertEquals("default", harness.tasks.lists().single().agentId)
        assertTrue(runCatching { harness.saveTasksSettings(kinuvo.protocol.TasksSettings(provider = "todoist")) }.isFailure)
        assertTrue(runCatching { harness.saveTasksSettings(kinuvo.protocol.TasksSettings(lists = mapOf("Inbox" to "ghost"))) }.isFailure)
        harness.saveTasksSettings(kinuvo.protocol.TasksSettings(lists = mapOf("Inbox" to "")))
        assertEquals(null, harness.tasks.lists().single().agentId)
    }
}
