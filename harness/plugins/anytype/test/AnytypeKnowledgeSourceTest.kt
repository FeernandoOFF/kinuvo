package kinuvo.harness.anytype

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kinuvo.harness.core.knowledge.BodyEdit
import kinuvo.harness.core.knowledge.NoteEdit
import kinuvo.protocol.MAX_ATTACHMENT_BYTES
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class AnytypeKnowledgeSourceTest {
    private val config = mapOf("apiKey" to "secret", "space" to "space-1")
    private val anytype = FakeAnytype()
    private val source = AnytypeKnowledgeSource({ config }, HttpClient(MockEngine { anytype.handle(this, it) }))

    @Test
    fun schemaHasTheSpacesTypesAndUserProperties() = runTest {
        val schema = source.schema()
        assertEquals(listOf("profile", "page"), schema.types.map { it.key })
        val human = schema.types.first()
        assertEquals("Human", human.name)
        assertEquals("Someone I know", human.description)
        assertEquals(listOf("email", "status", "tag", "met", "friend", "company"), human.properties)
        assertFalse(schema.open)
        assertEquals(listOf("email", "status", "tag", "met", "friend", "company", "age"), schema.properties.map { it.key })
        assertEquals("Status", schema.properties.single { it.key == "status" }.name)
        assertEquals("select", schema.properties.single { it.key == "status" }.format)
    }

    @Test
    fun searchFiltersByTypeAndKeepsSnippets() = runTest {
        anytype.add(ANA, "profile", "Ana Lopez", text = "Met Ana at the climbing gym")
        anytype.add(PAGE, "page", "Ana's party")
        anytype.add(TEMPLATE, "template", "Ana template")
        val all = source.search("ana")
        assertEquals(listOf(ANA, PAGE), all.map { it.ref })
        assertEquals("Met Ana at the climbing gym", all.first().snippet)
        assertEquals(listOf(ANA), source.search("ana", "profile").map { it.ref })
        assertEquals("""type IN ("profile")""", anytype.bodies("search").last()["filter"]!!.jsonPrimitive.content)
        assertEquals(1, source.search("ana", limit = 1).size)
    }

    @Test
    fun readsFilesLinkedFromBodies() = runTest {
        anytype.add(PHOTO, "image", "Climbing wall", properties = mapOf("file_ext" to JsonPrimitive("jpg")))
        anytype.files[PHOTO] = ContentType.Image.JPEG to byteArrayOf(1, 2, 3)
        anytype.add(ANA, "profile", "Ana", blocks = listOf(
            block("image", null, extra = mapOf("object_id" to JsonPrimitive(PHOTO), "name" to JsonPrimitive("Climbing wall"))),
        ))
        assertEquals("[[$PHOTO|Climbing wall]] (image)", source.read(ANA)!!.body)
        val file = assertNotNull(source.file(PHOTO))
        assertEquals("Climbing wall.jpg", file.name)
        assertEquals("image/jpeg", file.mimeType)
        assertContentEquals(byteArrayOf(1, 2, 3), file.bytes)
        assertNull(source.file(PAGE))

        anytype.add(REPORT, "file", "report.txt")
        anytype.files[REPORT] = ContentType.Text.Plain to "hi".encodeToByteArray()
        assertEquals("report.txt", source.file(REPORT)!!.name)
        anytype.files[REPORT] = ContentType.Application.Pdf to ByteArray(MAX_ATTACHMENT_BYTES + 1)
        assertFailsWith<IllegalArgumentException> { source.file(REPORT) }
    }

    @Test
    fun readsNativeValuesAndCanonicalLinks() = runTest {
        anytype.add(PAGE, "page", "CBRE")
        anytype.add(ANA, "profile", "Ana", properties = mapOf(
            "email" to JsonPrimitive("ana@example.org"), "status" to JsonArray(listOf(JsonPrimitive("Active"))),
            "tag" to JsonArray(listOf(JsonPrimitive("friends"), JsonPrimitive("climbing"))), "friend" to JsonPrimitive(true),
            "met" to JsonPrimitive("2026-10-04T00:00:00Z"), "company" to JsonArray(listOf(JsonPrimitive(PAGE))), "age" to JsonPrimitive(33),
            "created_date" to JsonPrimitive("2026-10-01T00:00:00Z"), "tag2" to JsonNull,
        ), blocks = listOf(
            block("paragraph", "Works at <mention object_id=\"$PAGE\">CBRE</mention> and [the office](anytype://object?objectId=$PAGE&spaceId=space-1)"),
            block("paragraph", ""),
            block("heading_2", "Hobbies"),
            block("bulleted_list_item", "Climbing"),
            block("bulleted_list_item", "Indoor", indent = 1),
            block("checkbox", "Call her", extra = mapOf("checked" to JsonPrimitive(true))),
            block("link", null, extra = mapOf("object_id" to JsonPrimitive(PAGE))),
        ))
        val note = assertNotNull(source.read(ANA))
        assertEquals("profile", note.type)
        assertEquals("Ana", note.title)
        assertEquals(mapOf("email" to "ana@example.org", "status" to "Active", "tag" to """["friends","climbing"]""", "friend" to "true",
            "met" to "2026-10-04T00:00:00Z", "company" to """["$PAGE"]""", "age" to "33"), note.properties)
        assertEquals("""
            Works at [[$PAGE|CBRE]] and [[$PAGE|the office]]

            ## Hobbies

            - Climbing
              - Indoor
            - [x] Call her

            [[$PAGE|CBRE]]
        """.trimIndent(), note.body)
        val requests = anytype.requests.size
        assertNull(source.read("Ana"))
        assertEquals(requests, anytype.requests.size)
        assertNull(source.read("bafyreimissingmissingmissing"))
    }

    @Test
    fun createsObjectsAndUndoArchivesThem() = runTest {
        anytype.add(PAGE, "page", "CBRE")
        val written = source.write(NoteEdit(null, "profile", "Ana", mapOf("tag" to """["friends"]""", "friend" to "true", "age" to "33", "email" to null),
            listOf(BodyEdit.Append("Works at [[$PAGE|CBRE]]"))))
        val post = anytype.bodies("objects").single()
        assertFalse("formatVersion" in post)
        assertEquals("Ana", post["name"]!!.jsonPrimitive.content)
        assertEquals("profile", post["type"]!!.jsonPrimitive.content)
        assertEquals("none", post["template"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"tag":["friends"],"friend":true,"age":33}"""), post["properties"])
        val note = written.note
        assertEquals("Works at [[$PAGE|CBRE]]", note.body)
        assertEquals("Works at <mention object_id=\"$PAGE\">CBRE</mention>", anytype.objects.getValue(note.ref).blocks.single()["text"]!!.jsonPrimitive.content)
        assertEquals(true, written.undo.jsonObject["created"]!!.jsonPrimitive.boolean)
        source.undo(written.undo)
        assertNull(source.read(note.ref))
        source.undo(written.undo)
    }

    @Test
    fun createsFromATemplateAndAppendsUnderItsHeadings() = runTest {
        anytype.add(TEMPLATE, "template", "Contact", properties = mapOf("target_type" to JsonPrimitive("profile"), "email" to JsonPrimitive("x@example.org")),
            blocks = listOf(block("heading_2", "Notes"), block("heading_2", "Links")))
        anytype.add("bafyreitemplate2000000000000", "template", "Contact", properties = mapOf("target_type" to JsonPrimitive("profile")))
        assertEquals(listOf("Contact ($TEMPLATE)", "Contact (bafyreitemplate2000000000000)"), source.schema().types.first().templates)
        val written = source.write(NoteEdit(null, "profile", "Ana", mapOf("email" to null), listOf(BodyEdit.Append("Climbs", "Notes")), "Contact ($TEMPLATE)"))
        assertEquals(TEMPLATE, anytype.bodies("objects").single()["template"]!!.jsonPrimitive.content)
        assertEquals("## Notes\n\nClimbs\n\n## Links", written.note.body)
        assertEquals(emptyMap(), written.note.properties)
        source.undo(written.undo)
        assertNull(source.read(written.note.ref))
    }

    @Test
    fun updatesPropertiesAndBodyAndUndoesExactly() = runTest {
        anytype.add(PAGE, "page", "CBRE")
        anytype.add(ANA, "profile", "Ana", properties = mapOf("email" to JsonPrimitive("ana@example.org"), "status" to JsonArray(listOf(JsonPrimitive("Active")))),
            blocks = listOf(
                block("paragraph", "Joined [cbre](https://cbre.com) in August, cbre is great"),
                block("heading_2", "Work"),
                block("bulleted_list_item", "Analyst"),
                block("bulleted_list_item", "Promoted once", indent = 1),
                block("paragraph", ""),
                block("heading_2", "Notes"),
                block("paragraph", "Likes <mention object_id=\"$PAGE\">CBRE</mention> coffee"),
            ))
        val before = anytype.objects.getValue(ANA).copy()
        val written = source.write(NoteEdit(ANA, title = "Ana Lopez", set = mapOf("status" to "Away", "email" to null, "age" to "34"), body = listOf(
            BodyEdit.Append("- Promoted in October", under = "Work"),
            BodyEdit.Append("Likes climbing.", under = "Hobbies"),
            BodyEdit.Link("CBRE", PAGE),
            BodyEdit.Replace("[[$PAGE|CBRE]] coffee", "[[$PAGE|CBRE]] tea"),
        )))
        assertEquals("Ana Lopez", written.note.title)
        assertEquals(mapOf("status" to "Away", "age" to "34"), written.note.properties)
        assertEquals("""
            Joined [cbre](https://cbre.com) in August, [[$PAGE|cbre]] is great

            ## Work

            - Analyst
              - Promoted once
            - Promoted in October

            ## Notes

            Likes [[$PAGE|CBRE]] tea

            ## Hobbies

            Likes climbing.
        """.trimIndent(), written.note.body)
        val set = anytype.patches().first().single()
        assertEquals(Json.parseToJsonElement("""{"op":"set_properties","set":{"status":["Away"],"age":34,"name":"Ana Lopez"},"unset":["email"]}"""), set)
        assertTrue(anytype.patchIfMatch.all { it != null })
        assertEquals("Likes <mention object_id=\"$PAGE\">CBRE</mention> tea", anytype.objects.getValue(ANA).blocks.last { it["type"]!!.jsonPrimitive.content == "paragraph" && it["text"]!!.jsonPrimitive.content.startsWith("Likes <") }["text"]!!.jsonPrimitive.content)

        source.undo(written.undo)
        val after = anytype.objects.getValue(ANA)
        assertEquals(before.properties, after.properties)
        assertEquals(before.blocks, after.blocks)
        source.undo(written.undo)
    }

    @Test
    fun undoRefusesWhenTheObjectChangedSince() = runTest {
        anytype.add(ANA, "profile", "Ana", blocks = listOf(block("paragraph", "Hello")))
        val written = source.write(NoteEdit(ANA, body = listOf(BodyEdit.Append("World"))))
        val ana = anytype.objects.getValue(ANA)
        ana.properties += "email" to JsonPrimitive("ana@example.org")
        ana.etag++
        assertFailsWith<IllegalStateException> { source.undo(written.undo) }
        assertEquals(2, ana.blocks.size)
    }

    @Test
    fun undoOfCreatedNoteUsesTheCheckedETag() = runTest {
        val written = source.write(NoteEdit(null, "profile", "Ana"))
        anytype.beforeDelete = { it.etag++ }
        assertFailsWith<IllegalStateException> { source.undo(written.undo) }
        assertNotNull(source.read(written.note.ref))
    }

    @Test
    fun undoesNewestFirst() = runTest {
        anytype.add(ANA, "profile", "Ana", blocks = listOf(block("paragraph", "Hello")))
        val first = source.write(NoteEdit(ANA, set = mapOf("email" to "ana@example.org"), body = listOf(BodyEdit.Append("World"))))
        val second = source.write(NoteEdit(ANA, body = listOf(BodyEdit.Replace("World", "Everyone"))))
        assertFailsWith<IllegalStateException> { source.undo(first.undo) }
        source.undo(second.undo)
        assertEquals(first.note, source.read(ANA))
        source.undo(first.undo)
        assertEquals("Hello", source.read(ANA)!!.body)
        assertEquals(emptyMap(), source.read(ANA)!!.properties)
    }

    @Test
    fun writesFailOnStaleETags() = runTest {
        anytype.add(ANA, "profile", "Ana", blocks = listOf(block("paragraph", "Hello")))
        anytype.bumpBeforePatch = true
        val error = assertFailsWith<IllegalStateException> { source.write(NoteEdit(ANA, body = listOf(BodyEdit.Append("World")))) }
        assertTrue("changed" in error.message!!)
        assertEquals(1, anytype.objects.getValue(ANA).blocks.size)
    }

    @Test
    fun failedEditsRollBackOnlyAgainstTheirSavedETag() = runTest {
        anytype.add(ANA, "profile", "Ana", properties = mapOf("email" to JsonPrimitive("old@x.com")))
        assertFailsWith<IllegalArgumentException> {
            source.write(NoteEdit(ANA, set = mapOf("email" to "new@x.com"), body = listOf(BodyEdit.Replace("missing", "text"))))
        }
        assertEquals("old@x.com", anytype.objects.getValue(ANA).properties["email"]!!.jsonPrimitive.content)
        assertTrue(anytype.patchIfMatch.all { it != null })

        var patches = 0
        anytype.beforePatch = { o ->
            if (++patches == 2) {
                o.properties += "email" to JsonPrimitive("user@x.com")
                o.etag++
            }
        }
        assertFailsWith<IllegalStateException> {
            source.write(NoteEdit(ANA, set = mapOf("email" to "new@x.com"), body = listOf(BodyEdit.Append("Hello"))))
        }
        assertEquals("user@x.com", anytype.objects.getValue(ANA).properties["email"]!!.jsonPrimitive.content)
        assertTrue(anytype.patchIfMatch.all { it != null })
    }

    @Test
    fun reloadingAfterWriteDoesntAbsorbConcurrentEditsIntoUndo() = runTest {
        anytype.add(ANA, "profile", "Ana")
        anytype.afterPatch = { o ->
            o.properties += "email" to JsonPrimitive("user@x.com")
            o.etag++
        }
        assertFailsWith<IllegalStateException> { source.write(NoteEdit(ANA, set = mapOf("email" to "agent@x.com"))) }
        assertEquals("user@x.com", anytype.objects.getValue(ANA).properties["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun snapshotsKeepRequestsInOneSpace() = runTest {
        anytype.add(ANA, "profile", "Ana")
        var options = config
        val changing = AnytypeKnowledgeSource({ options }, HttpClient(MockEngine {
            options = config + ("space" to "space-2")
            anytype.handle(this, it)
        }))
        val captured = changing.snapshot()
        assertEquals("Ana", captured.read(ANA)!!.title)
        assertEquals("anytype:http://127.0.0.1:31009:space-1", captured.scope)
        assertTrue(anytype.requests.all { it.first.url.encodedPath.startsWith("/v2/spaces/space-1/") })
    }

    @Test
    fun replaceAndLinkRefuseWhatTheyCantDoInOneBlock() = runTest {
        anytype.add(ANA, "profile", "Ana", blocks = listOf(block("paragraph", "Hello there"), block("paragraph", "Hello world")))
        assertFailsWith<IllegalArgumentException> { source.write(NoteEdit(ANA, body = listOf(BodyEdit.Replace("Hello", "Hi")))) }
        assertFailsWith<IllegalArgumentException> { source.write(NoteEdit(ANA, body = listOf(BodyEdit.Replace("there\n\nHello", "Hi")))) }
        assertFailsWith<IllegalArgumentException> { source.write(NoteEdit(ANA, body = listOf(BodyEdit.Replace("Bye", "Hi")))) }
        assertFailsWith<IllegalArgumentException> { source.write(NoteEdit(ANA, body = listOf(BodyEdit.Link("Paris", PAGE)))) }
        assertFailsWith<IllegalArgumentException> { source.write(NoteEdit(ANA, set = mapOf("age" to "old"))) }
        assertFailsWith<IllegalArgumentException> { source.write(NoteEdit(ANA, set = mapOf("created_date" to "2026-01-01"))) }
        assertTrue(anytype.patches().isEmpty())
    }

    @Test
    fun checksOptionsAndHidesTheKey() = runTest {
        source.checkOptions(config)
        source.checkOptions(config + ("url" to "https://anytype.example.org:31009"))
        assertFailsWith<IllegalArgumentException> { source.checkOptions(mapOf("space" to "s")) }
        assertFailsWith<IllegalArgumentException> { source.checkOptions(config + ("url" to "ftp://host")) }
        assertFailsWith<IllegalArgumentException> { source.checkOptions(config + ("url" to "http://u:p@host")) }
        assertFailsWith<IllegalArgumentException> { source.checkOptions(config + ("url" to "http://host/?q=1")) }
        val failing = AnytypeKnowledgeSource({ config }, HttpClient(MockEngine { respond("""{"message":"Rejected key secret"}""", HttpStatusCode.Unauthorized) }))
        val error = assertFailsWith<IllegalStateException> { failing.schema() }
        assertFalse("secret" in error.message!!)
        val closed = AnytypeKnowledgeSource({ config }, HttpClient(MockEngine { throw java.net.ConnectException() }))
        assertEquals(
            "Can't reach Anytype at http://127.0.0.1:31009: open the Anytype app on the Server (its API only runs while it's open), or check the API URL",
            assertFailsWith<IllegalStateException> { closed.schema() }.message,
        )
        assertEquals("anytype:http://127.0.0.1:31009:space-1", source.scope)
    }

    private fun block(type: String, text: String?, indent: Int = 0, extra: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
        put("type", type)
        text?.let { put("text", it) }
        if (indent > 0) put("indent", indent)
        extra.forEach { (k, v) -> put(k, v) }
    }

    private companion object {
        const val ANA = "bafyreiana00000000000000000000"
        const val PAGE = "bafyreicbre0000000000000000000"
        const val TEMPLATE = "bafyreitemplate00000000000000"
        const val PHOTO = "bafyreiphoto000000000000000000"
        const val REPORT = "bafyreireport00000000000000000"
    }
}

/** A tiny in-memory Anytype API v2. */
private class FakeAnytype {
    data class Obj(val type: String, var properties: Map<String, JsonElement>, var blocks: List<JsonObject>, var etag: Int = 1, var archived: Boolean = false)

    val objects = mutableMapOf<String, Obj>()
    val files = mutableMapOf<String, Pair<ContentType, ByteArray>>()
    val requests = mutableListOf<Pair<HttpRequestData, JsonObject?>>()
    val patchIfMatch = mutableListOf<String?>()
    var bumpBeforePatch = false
    var beforePatch: ((Obj) -> Unit)? = null
    var beforeDelete: ((Obj) -> Unit)? = null
    var afterPatch: ((Obj) -> Unit)? = null
    private var ids = 0
    private val json = ContentType.Application.Json

    fun add(id: String, type: String, name: String, properties: Map<String, JsonElement> = emptyMap(), blocks: List<JsonObject> = emptyList(), text: String = "") {
        objects[id] = Obj(type, mapOf("name" to JsonPrimitive(name)) + properties, blocks.map(::withId) + if (text.isEmpty()) emptyList() else listOf(withId(buildJsonObject { put("type", "paragraph"); put("text", text) })))
    }

    fun bodies(path: String) = requests.filter { it.first.url.encodedPath.endsWith("/$path") && it.second != null }.map { it.second!! }
    fun patches() = requests.filter { it.first.method == HttpMethod.Patch }.map { it.second!!.getValue("ops").jsonArray }

    fun handle(scope: MockRequestHandleScope, request: HttpRequestData) = with(scope) {
        assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
        val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
        requests += request to body
        val path = request.url.encodedPath.removePrefix("/v2/spaces/space-1/")
        val params = request.url.parameters
        when {
            path == "properties" -> page(PROPERTIES, params)
            path == "types" -> page(TYPES.map { buildJsonObject { put("key", it.first); put("name", it.second) } }, params)
            path == "templates" -> page(objects.filter { (_, o) -> o.type == "template" && !o.archived }.map { (id, o) ->
                buildJsonObject { put("id", id); put("name", name(o)); put("template_for", o.properties["target_type"] ?: JsonPrimitive("")) }
            }, params)
            path.startsWith("types/") -> {
                val key = path.removePrefix("types/")
                ok(buildJsonObject {
                    putJsonObject("properties") { put("name", key); put("description", if (key == "profile") "Someone I know" else "") }
                    putJsonObject("type_settings") {
                        putJsonArray("property_definitions") {
                            (listOf("type", "backlinks") + if (key == "profile") listOf("email", "status", "tag", "met", "friend", "company") else emptyList())
                                .forEach { add(buildJsonObject { put("property", it) }) }
                        }
                    }
                })
            }
            path == "search" -> {
                val type = body!!["filter"]?.jsonPrimitive?.content?.let { Regex("\"(.*)\"").find(it)!!.groupValues[1] }
                val query = body.getValue("query").jsonPrimitive.content
                page(objects.filter { (_, o) -> !o.archived && (type == null || o.type == type) && name(o).contains(query, true) }.map { (id, o) ->
                    buildJsonObject {
                        put("id", id); put("name", name(o)); put("type", o.type)
                        putJsonObject("properties") { put("snippet", o.blocks.joinToString("\n") { it["text"]?.jsonPrimitive?.content.orEmpty() }) }
                    }
                }, params)
            }
            path == "objects" -> {
                val id = "bafyreicreated${++ids}000000000000"
                val props = body!!.getValue("properties").jsonObject
                val template = body.getValue("template").jsonPrimitive.content.takeIf { it != "none" }?.let { objects.getValue(it) }
                val inherited = template?.properties.orEmpty() - setOf("name", "target_type")
                objects[id] = Obj(body.getValue("type").jsonPrimitive.content, inherited + props + ("name" to body.getValue("name")), template?.blocks.orEmpty().map { JsonObject(it - "id") }.map(::withId))
                ok(buildJsonObject { put("id", id); put("etag", "e1") }, HttpStatusCode.Created)
            }
            path.startsWith("files/") && path.endsWith("/content") -> files[path.removePrefix("files/").removeSuffix("/content")]
                ?.let { (type, bytes) -> respond(bytes, headers = headersOf(HttpHeaders.ContentType, type.toString())) }
                ?: respond("""{"status":404}""", HttpStatusCode.NotFound)
            path.startsWith("objects/") -> {
                val id = path.removePrefix("objects/")
                val o = objects[id] ?: return@with respond("""{"status":404}""", HttpStatusCode.NotFound)
                when (request.method) {
                    HttpMethod.Get -> respond(buildJsonObject {
                        put("id", id); put("type", o.type)
                        put("properties", JsonObject(o.properties + if (o.archived) mapOf("is_archived" to JsonPrimitive(true)) else emptyMap()))
                        put("blocks", JsonArray(o.blocks))
                    }.toString(), headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"e${o.etag}\"")))
                    HttpMethod.Delete -> {
                        beforeDelete?.invoke(o)
                        if (request.headers[HttpHeaders.IfMatch] != "\"e${o.etag}\"") {
                            return@with respond("""{"status":409,"code":"etag_mismatch","message":"the object changed"}""", HttpStatusCode.Conflict)
                        }
                        o.archived = true
                        ok(buildJsonObject { put("id", id) })
                    }
                    else -> {
                        assertEquals("full", params["ids"])
                        patchIfMatch += request.headers[HttpHeaders.IfMatch]
                        beforePatch?.invoke(o)
                        if (bumpBeforePatch) o.etag++
                        if (request.headers[HttpHeaders.IfMatch]?.let { it != "\"e${o.etag}\"" } == true) {
                            return@with respond("""{"status":409,"code":"etag_mismatch","message":"the object changed"}""", HttpStatusCode.Conflict)
                        }
                        val created = mutableMapOf<String, String>()
                        body!!.getValue("ops").jsonArray.forEachIndexed { i, op -> apply(o, i, op.jsonObject, created) }
                        o.etag++
                        val saved = o.etag
                        afterPatch?.invoke(o)
                        ok(buildJsonObject { put("etag", "e$saved"); put("created_blocks", JsonObject(created.mapValues { JsonPrimitive(it.value) })) })
                    }
                }
            }
            else -> error("Unexpected $path")
        }
    }

    private fun apply(o: Obj, i: Int, op: JsonObject, created: MutableMap<String, String>) {
        when (op.getValue("op").jsonPrimitive.content) {
            "set_properties" -> o.properties = o.properties + op["set"]?.jsonObject.orEmpty() - op["unset"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet()
            "update_block" -> o.blocks = o.blocks.map { b ->
                if (b.id == op.getValue("id").jsonPrimitive.content) JsonObject(b + op.getValue("set").jsonObject) else b
            }
            "delete_block" -> o.blocks = o.blocks.filter { it.id != op.getValue("id").jsonPrimitive.content }
            "insert_blocks" -> {
                val inside = op["inside"]?.jsonPrimitive?.content
                val after = op["after"]?.jsonPrimitive?.content ?: inside
                val base = after?.let { a -> o.blocks.first { it.id == a }.indent + if (inside != null) 1 else 0 } ?: 0
                val parsed = op.getValue("markdown").jsonPrimitive.content.lines().filter { it.isNotBlank() }.mapIndexed { j, line ->
                    val (type, text) = when {
                        line.startsWith("## ") -> "heading_2" to line.removePrefix("## ")
                        line.startsWith("- ") -> "bulleted_list_item" to line.removePrefix("- ")
                        else -> "paragraph" to line
                    }
                    withId(buildJsonObject { put("type", type); put("text", text); if (base > 0) put("indent", base) }).also { created["ops[$i].markdown[$j]"] = it.id }
                }
                val at = after?.let { a ->
                    val k = o.blocks.indexOfFirst { it.id == a }
                    (k + 1 until o.blocks.size).firstOrNull { o.blocks[it].indent <= o.blocks[k].indent } ?: o.blocks.size
                } ?: o.blocks.size
                o.blocks = o.blocks.take(at) + parsed + o.blocks.drop(at)
            }
            else -> error("Unexpected op $op")
        }
    }

    private fun withId(b: JsonObject) = if ("id" in b) b else JsonObject(mapOf("id" to JsonPrimitive("b${++ids}")) + b)
    private val JsonObject.id get() = getValue("id").jsonPrimitive.content
    private val JsonObject.indent get() = this["indent"]?.jsonPrimitive?.int ?: 0
    private fun name(o: Obj) = o.properties["name"]?.jsonPrimitive?.content.orEmpty()

    private fun MockRequestHandleScope.page(items: List<JsonObject>, params: Parameters) = run {
        val offset = params["offset"]?.toInt() ?: 0
        val limit = params["limit"]?.toInt() ?: 25
        ok(buildJsonObject {
            put("data", JsonArray(items.drop(offset).take(limit)))
            put("has_more", offset + limit < items.size)
        })
    }

    private fun MockRequestHandleScope.ok(body: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body.toString(), status, headersOf(HttpHeaders.ContentType, json.toString()))

    companion object {
        val TYPES = listOf("profile" to "Human", "page" to "Page", "template" to "Template")
        val PROPERTIES = listOf(
            "name" to "text", "email" to "email", "status" to "select", "tag" to "multi_select", "met" to "date", "friend" to "checkbox",
            "company" to "objects", "age" to "number", "created_date" to "date", "backlinks" to "objects",
        ).map { (key, format) -> buildJsonObject { put("key", key); put("name", key.replaceFirstChar { it.uppercase() } + " "); put("format", format) } }
    }
}
