package kinuvo.harness.core.knowledge

import kinuvo.harness.testing.*
import ai.koog.prompt.message.Message

import kinuvo.harness.core.modelAlias
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.TestHome
import kinuvo.harness.core.store.FileKnowledgeChangeLog
import kinuvo.harness.core.store.MarkdownKnowledgeSource
import kinuvo.harness.core.store.readText
import kinuvo.harness.core.store.writeAtomically
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.ReflectionResult
import kinuvo.protocol.KnowledgeChange
import kinuvo.protocol.KnowledgeSchema
import kinuvo.protocol.Note
import kinuvo.protocol.NoteProperty
import kinuvo.protocol.NoteType
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowledgeTest {
    private val home = TestHome()
    private val root = Path(home.files.home, "vault")
    private val source = MarkdownKnowledgeSource(root)
    private val now = 1_790_000_000_000L // 2026-09-21
    private var conventions = ""
    private val knowledge = Knowledge({ source }, FileKnowledgeChangeLog(home.files.knowledgeChanges), { conventions }, clock = { now })

    @Test
    fun createsNotesInTheUsersTerms() = runTest {
        val cbre = knowledge.create("organization", "CBRE")
        val ana = knowledge.create("person", "Ana García", mapOf("email" to "ana@example.com"), "Met at the viewing. Works at [[CBRE]].")
        assertEquals("People/Ana García", ana.ref)
        assertEquals("Met at the viewing. Works at [[${cbre.ref}|CBRE]].", ana.body)
        val text = Path(root, "People", "Ana García.md").readText()
        assertTrue("type: \"person\"\nemail: \"ana@example.com\"\n---\n\nMet at the viewing." in text, text)
        assertEquals(ana, knowledge.find("ana garcía"))
        assertEquals(listOf("People/Ana García"), knowledge.search("example.com").map { it.ref })
        assertFailsWith<IllegalStateException> { knowledge.create("person", "Bob", body = "Knows [[Nobody]]") }
    }

    @Test
    fun updatesAppendUnderHeadingsAndLinkText() = runTest {
        knowledge.create("organization", "CBRE")
        knowledge.create("person", "Michelle", body = "## Work\n\n- Started in August\n\n## Family\n\nTwo kids.")
        val note = knowledge.update("Michelle", set = mapOf("company" to "CBRE"), body = listOf(
            BodyEdit.Append("- Promoted in October", under = "Work"),
            BodyEdit.Link("august", "CBRE"),
            BodyEdit.Append("Likes climbing.", under = "Hobbies"),
        ))
        assertEquals("## Work\n\n- Started in [[Organizations/CBRE|August]]\n- Promoted in October\n\n## Family\n\nTwo kids.\n\n## Hobbies\n\nLikes climbing.", note.body)
        assertEquals("CBRE", note.properties["company"])
        assertFailsWith<IllegalArgumentException> { knowledge.update("Michelle", body = listOf(BodyEdit.Link("Paris", "CBRE"))) }
        assertNull(knowledge.update("Michelle", set = mapOf("company" to null)).properties["company"])
    }

    @Test
    fun appendsIgnoreHeadingsInCodeFences() {
        val body = "```markdown\n## Work\n```\n\n## Work\n\nExamples:\n\n~~~\n## Other\n~~~\n\n## Family\n\nTwo kids."
        val result = MarkdownBody.apply(body, listOf(BodyEdit.Append("Promoted.", "Work")))
        assertEquals(body.replace("~~~\n\n## Family", "~~~\n\nPromoted.\n\n## Family"), result)
    }

    @Test
    fun keepsFrontmatterItDoesntChange() = runTest {
        val file = Path(root, "People", "Ana.md")
        writeAtomically(file, "---\ntype: person\ntags:\n- friend\ncreated: 2024-01-01\n# a comment\n---\n\nMy notes.\n")
        knowledge.update("Ana", set = mapOf("email" to "ana@x.com", "created" to "2026-10-04"), body = listOf(BodyEdit.Append("Moved to Cork")))
        assertEquals("---\ntype: person\ntags:\n- friend\ncreated: \"2026-10-04\"\n# a comment\nemail: \"ana@x.com\"\n---\n\nMy notes.\n\nMoved to Cork\n", file.readText())
    }

    @Test
    fun obsidianListsAndLinksInFrontmatterAreRead() = runTest {
        writeAtomically(Path(root, "People", "Ana.md"), "---\ntype: [[Person]]\ntags: [friend, work]\naliases:\n  - Ani\ncompany: [[Acme]]\nemployer: [[Acme, Inc]]\n---\nMet at a conference.\n")
        val note = assertNotNull(source.read("People/Ana"))
        assertEquals("Person", note.type)
        assertEquals("friend, work", note.properties["tags"])
        assertEquals("Ani", note.properties["aliases"])
        assertEquals("[[Acme]]", note.properties["company"])
        assertEquals("[[Acme, Inc]]", note.properties["employer"])
    }

    @Test
    fun notesWithoutAnyKinuvoFrontmatterWork() = runTest {
        writeAtomically(Path(root, "Inbox", "Ideas.md"), "Write a book.\n")
        val note = knowledge.update("Ideas", body = listOf(BodyEdit.Append("- Learn Portuguese")))
        assertEquals("note", note.type)
        assertEquals("Write a book.\n\n- Learn Portuguese", note.body)
    }

    @Test
    fun propertyUpdatesPreserveTheBodyAndBlankAppendsDontWrite() = runTest {
        val file = Path(root, "People", "Ana.md")
        val body = "\nMy notes.  \n\n"
        writeAtomically(file, "---\ntype: person\n---\n$body")
        knowledge.update("Ana", set = mapOf("email" to "a@x.com"))
        assertTrue(file.readText().endsWith("---\n$body"))
        val before = file.readText()
        assertFailsWith<IllegalArgumentException> { knowledge.update("Ana", body = listOf(BodyEdit.Append(" \n"))) }
        assertFailsWith<IllegalArgumentException> { knowledge.update("Ana") }
        assertEquals(before, file.readText())
        assertEquals(1, knowledge.changes().size)
        assertFailsWith<IllegalArgumentException> { MarkdownBody.apply("Hello", listOf(BodyEdit.Link("", "Ana"))) }
        assertFailsWith<IllegalArgumentException> { MarkdownBody.apply("Hello [Ana](url)", listOf(BodyEdit.Link("Hello [Ana", "Ana"))) }
    }

    @Test
    fun schemaNamesResolveToKeysAndAmbiguitiesAreRefused() = runTest {
        var template: String? = null
        val native = object : KnowledgeSource by source {
            override fun snapshot(): KnowledgeSource = this
            override suspend fun write(edit: NoteEdit) = source.write(edit).also { template = edit.template }
            override suspend fun schema() = KnowledgeSchema(
                listOf(NoteType("profile", "Human", templates = listOf("Contact"))),
                listOf(NoteProperty("other", "email"), NoteProperty("email", "Address"), NoteProperty("one", "Status"), NoteProperty("two", "Status")),
            )
        }
        val k = Knowledge({ native }, FileKnowledgeChangeLog(home.files.knowledgeChanges))
        val ana = k.create("Human", "Ana", mapOf("email" to "a@x.com"))
        assertEquals("profile", ana.type)
        assertEquals(mapOf("email" to "a@x.com"), ana.properties)
        assertEquals(listOf(ana.ref), k.search("Ana", "Human").map { it.ref })
        assertNull(k.find(ana.ref, "page"))
        assertFailsWith<IllegalArgumentException> { k.update(ana.ref, set = mapOf("Status" to "Active")) }
        assertFailsWith<IllegalArgumentException> { k.update(ana.ref, set = mapOf("email" to "a", "Address" to "b")) }
        assertFailsWith<IllegalArgumentException> { k.create("Unknown", "Sam") }
        assertFailsWith<IllegalStateException> { k.create("Human", "Sam", template = "Meeting") }
        k.create("Human", "Sam", template = "contact")
        assertEquals("Contact", template)
        writeAtomically(Path(root, "Other", "Ana.md"), "---\ntype: profile\n---\n")
        assertFailsWith<IllegalArgumentException> { k.update("Ana", set = mapOf("email" to "other")) }
        assertEquals(2, k.changes().size)
    }

    @Test
    fun updateKeepsTheSourceThatResolvedTheNote() = runTest {
        val ana = knowledge.create("person", "Ana")
        val other = MarkdownKnowledgeSource(Path(home.files.home, "other"))
        var selected: KnowledgeSource = source
        val switching = object : KnowledgeSource by source {
            override fun snapshot(): KnowledgeSource = this
            override suspend fun read(ref: String): Note? = source.read(ref).also { selected = other }
        }
        selected = switching
        val k = Knowledge({ selected }, FileKnowledgeChangeLog(home.files.knowledgeChanges))
        k.update(ana.ref, body = listOf(BodyEdit.Append("Hello")))
        assertEquals("Hello", source.read(ana.ref)!!.body)
        assertNull(other.read(ana.ref))
        assertEquals(source.scope, k.changes().first().scope)
        assertFailsWith<IllegalStateException> { k.undo(k.changes().first().id) }

        var folder = root
        val dynamic = MarkdownKnowledgeSource { folder }
        val captured = dynamic.snapshot()
        folder = Path(home.files.home, "other")
        assertEquals(source.scope, captured.scope)
        assertEquals("Hello", captured.read(ana.ref)!!.body)
    }

    @Test
    fun failedChangeLogRevertsTheWriteAndObserversCantFailIt() = runTest {
        val log = FileKnowledgeChangeLog(home.files.knowledgeChanges)
        val failing = object : KnowledgeChangeLog by log {
            override suspend fun add(change: KnowledgeChange) { error("Disk full") }
        }
        assertFailsWith<IllegalStateException> { Knowledge({ source }, failing).create("person", "Ana") }
        assertNull(source.read("People/Ana"))
        knowledge.onChange { error("Observer failed") }
        knowledge.create("person", "Ana")
        assertEquals(1, knowledge.changes().size)
    }

    @Test
    fun changesUndoNewestFirst() = runTest {
        knowledge.create("person", "Sam")
        knowledge.update("Sam", body = listOf(BodyEdit.Append("Likes chess")))
        val (appended, created) = knowledge.changes()
        assertEquals("Sam: Likes chess", appended.summary)
        assertFailsWith<IllegalStateException> { knowledge.undo(created.id) }
        knowledge.undo(appended.id)
        assertEquals("", knowledge.find("Sam")!!.body)
        knowledge.undo(created.id)
        assertNull(knowledge.find("Sam"))
        assertTrue(knowledge.changes().all { it.undone })
    }

    @Test
    fun changeSummariesDropMarkdownSyntax() = runTest {
        knowledge.create("person", "Sam")
        knowledge.update("Sam", body = listOf(BodyEdit.Append("## Work\n- At [CBRE](https://cbre.com)")))
        assertEquals("Sam: Work", knowledge.changes().first().summary)
    }

    @Test
    fun undoCanRetryWhenOnlyTheLogFailed() = runTest {
        val log = FileKnowledgeChangeLog(home.files.knowledgeChanges)
        var fail = true
        val flaky = object : KnowledgeChangeLog by log {
            override suspend fun markUndone(id: String) {
                if (fail) { fail = false; error("Disk full") }
                log.markUndone(id)
            }
        }
        val k = Knowledge({ source }, flaky)
        k.create("person", "Ana")
        k.update("Ana", body = listOf(BodyEdit.Append("Hello")))
        val change = k.changes().first()
        assertFailsWith<IllegalStateException> { k.undo(change.id) }
        assertEquals("", k.find("Ana")!!.body)
        assertTrue(k.undo(change.id).undone)
    }

    @Test
    fun toolsTakeTheUsersConventionsAndNeedApprovalToRewrite() = runTest {
        conventions = "People go in person notes; facts under ## Timeline."
        val plugin = KnowledgePlugin(knowledge)
        val agent = home.agent.copy(config = home.agent.config.copy(tools = listOf("knowledge")))
        assertTrue(conventions in plugin.instructions(agent)!!.body)
        val tools = plugin.prepare(kinuvo.harness.core.RunContext(agent, "s1"), activate = true).tools.associateBy { it.descriptor.name }
        val tool = tools.getValue("knowledge")
        tool.execute(buildJsonObject { put("action", "create"); put("type", "person"); put("title", "Ana"); put("set", """{"email":"a@x.com"}""") })
        tool.execute(buildJsonObject { put("action", "update"); put("ref", "Ana"); put("append", "- Met at work"); put("under", "Timeline") })
        assertTrue("## Timeline\n\n- Met at work" in tool.execute(buildJsonObject { put("action", "read"); put("ref", "Ana") }))
        assertTrue("person (person)" in tool.execute(buildJsonObject { put("action", "schema") }))
        val rewrite = tools.getValue("knowledge_rewrite")
        assertEquals(kinuvo.protocol.Risk.Medium, rewrite.risk)
        rewrite.execute(buildJsonObject { put("ref", "Ana"); put("old", "Met at work"); put("new", "Met at the gym") })
        assertEquals("## Timeline\n\n- Met at the gym", knowledge.find("Ana")!!.body)
        assertEquals("s1", knowledge.changes().first().sessionId)
    }

    @Test
    fun reflectionStagesWhatTheModelPicks() = runTest {
        val model = scriptedModel { prompt, tools ->
            val instructions = prompt.messages.filterIsInstance<Message.System>().firstOrNull()?.textContent().orEmpty()
            val history = prompt.messages
            assertTrue("Today is 2026-10-04." in history.first { it is Message.User && it.textContent().startsWith("Background Reflection instructions:") }.textContent())
            if (tools.single().name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias() && history.toolResults.isEmpty()) flowOf(
                StreamFrame.ToolCallComplete("c1", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias(), """{"action":"create","type":"person","title":"Ana","set":"{\"relationship\":\"sister\"}","reason":"Family relationship","evidence":"user-0: sister Ana"}"""),
                StreamFrame.End(),
            ) else flowOf(StreamFrame.TextDelta("Saved Ana."), StreamFrame.End())
        }
        val entries = listOf(SessionEntry.UserMessage("My sister Ana is visiting"), SessionEntry.AssistantMessage("Nice!"))
        val reflection = Reflection(knowledge, home.docs) { LocalDate(2026, 10, 4) }
        val result = reflection.reflect(home.agent, model, "s1", entries)
        assertEquals(null, result.error)
        assertEquals(1, result.proposals.size)
        assertEquals(null, knowledge.find("Ana"))
        assertEquals(ReflectionResult(), reflection.reflect(home.agent, model, "s1", emptyList()))
    }
}
