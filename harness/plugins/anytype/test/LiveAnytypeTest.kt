package kinuvo.harness.anytype

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kinuvo.harness.core.knowledge.BodyEdit
import kinuvo.harness.core.knowledge.NoteEdit
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.knowledgeConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

/** Runs against the Anytype space in `~/.kinuvo/knowledge.yaml`; see the README. */
class LiveAnytypeTest {
    private val options by lazy { AgentFiles(Path(System.getProperty("user.home"), ".kinuvo")).knowledgeConfig().options }

    @Test
    fun readsConfiguredSpace() = runBlocking {
        if (System.getenv("KINUVO_LIVE_ANYTYPE") != "1") return@runBlocking
        HttpClient(Java).use { client ->
            val source = AnytypeKnowledgeSource({ options }, client)
            val schema = source.schema()
            assertTrue(schema.types.isNotEmpty())
            assertTrue(schema.properties.none { it.key in AnytypeProperties.SYSTEM })
            val found = source.search("", limit = 5)
            assertTrue(found.isNotEmpty())
            val note = assertNotNull(source.read(found.first().ref))
            assertEquals(found.first().title, note.title)
            assertTrue(source.search("", note.type, 5).all { it.type == note.type })
        }
    }

    @Test
    fun editsAndUndoesTemporaryPage() = runBlocking {
        if (System.getenv("KINUVO_LIVE_ANYTYPE_WRITES") != "1") return@runBlocking
        var created: String? = null
        HttpClient(Java) { install(HttpTimeout) { requestTimeoutMillis = 30_000 } }.use { client ->
            client.plugin(HttpSend).intercept { request ->
                val call = execute(request)
                if (request.method == HttpMethod.Post && call.request.url.encodedPath.endsWith("/objects") && call.response.status.isSuccess()) {
                    created = Json.parseToJsonElement(call.response.bodyAsText()).jsonObject["id"]?.jsonPrimitive?.content
                }
                call
            }
            val source = AnytypeKnowledgeSource({ options }, client)
            try {
                val title = "Kinuvo integration test ${UUID.randomUUID()}"
                val ref = source.write(NoteEdit(null, "page", title, body = listOf(BodyEdit.Append("Intro paragraph about climbing")))).note.ref
                assertEquals(created, ref)
                source.write(NoteEdit(ref, body = listOf(BodyEdit.Append("- first", under = "Notes"))))
                val listed = source.write(NoteEdit(ref, body = listOf(BodyEdit.Append("- second", under = "Notes")))).note
                assertEquals("Intro paragraph about climbing\n\n## Notes\n\n- first\n- second", listed.body)

                val linked = source.write(NoteEdit(ref, body = listOf(BodyEdit.Link("Climbing", ref))))
                assertEquals("Intro paragraph about [[$ref|climbing]]\n\n## Notes\n\n- first\n- second", linked.note.body)
                val replaced = source.write(NoteEdit(ref, title = "$title renamed", set = mapOf("description" to "Temporary"),
                    body = listOf(BodyEdit.Replace("Intro paragraph about [[$ref|climbing]]", "Opening about [[$ref|climbing]]"))))
                assertEquals("$title renamed", replaced.note.title)
                assertEquals("Temporary", replaced.note.properties["description"])
                assertTrue(replaced.note.body.startsWith("Opening about [[$ref|climbing]]"))

                val later = source.write(NoteEdit(ref, body = listOf(BodyEdit.Append("Later"))))
                assertFailsWith<IllegalStateException> { source.undo(replaced.undo) }
                source.undo(later.undo)
                source.undo(replaced.undo)
                assertEquals(linked.note, source.read(ref))
                source.undo(linked.undo)
                assertEquals(listed, source.read(ref))
            } finally {
                created?.let { id ->
                    val space = options.getValue("space").encodeURLPathPart()
                    client.delete("${options["url"].orEmpty().ifBlank { AnytypeKnowledgeSource.DEFAULT_URL }.trimEnd('/')}/v2/spaces/$space/objects/${id.encodeURLPathPart()}") {
                        bearerAuth(options.getValue("apiKey"))
                    }
                }
            }
            val id = assertNotNull(created)
            withTimeout(10_000) { while (source.read(id) != null) delay(100) }
        }
    }
}
