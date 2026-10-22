package kinuvo.harness.core.artifacts

import kinuvo.harness.core.TestHome
import kinuvo.harness.core.store.FileArtifacts
import kinuvo.harness.core.store.isFile
import kinuvo.protocol.ArtifactChange
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactsTest {
    private val home = TestHome()
    private var now = 1L
    private val artifacts = Artifacts(FileArtifacts(home.files.artifacts)) { now++ }
    private val workspace = mapOf("out/chart.html" to "<svg/>")
    private val tool = ArtifactTool(home.agent, "s1", artifacts) { workspace[it] ?: error("missing $it") }

    private suspend fun call(vararg args: Pair<String, String>) = tool.execute(JsonObject(args.associate { (k, v) -> k to JsonPrimitive(v) }))

    @Test fun attributionFollowsTheActualLastSaverAndSurvivesReload() = runTest {
        val created = artifacts.create("first", "private-one", "Output", "txt", "one", roomSessionId = "public-one")
        assertEquals("public-one", Artifacts(FileArtifacts(home.files.artifacts)).get(created.id).info.roomSessionId)
        artifacts.update(created.id, "second", "private-two", roomSessionId = "public-two") { "two" }
        val updated = Artifacts(FileArtifacts(home.files.artifacts)).get(created.id).info
        assertEquals("second", updated.agentId)
        assertEquals("private-two", updated.sessionId)
        assertEquals("public-two", updated.roomSessionId)
        artifacts.update(created.id, "direct", "direct-session") { "three" }
        assertNull(artifacts.get(created.id).info.roomSessionId)
    }

    @Test
    fun createUpdateAndDeleteKeepOnlyTheLatest() = runTest {
        val changes = mutableListOf<ArtifactChange>()
        artifacts.onChange { changes += it }
        val saved = Json.parseToJsonElement(call("action" to "create", "title" to "Lisbon Plan!", "type" to "md", "content" to "# Day 1")).jsonObject
        val id = saved.getValue("id").jsonPrimitive.content
        assertTrue(id.startsWith("lisbon-plan-"))
        assertTrue(Path(home.files.artifacts, "$id.md").isFile())

        call("action" to "update", "id" to id, "old" to "Day 1", "new" to "Day one")
        assertEquals("# Day one", artifacts.get(id).content)
        assertFailsWith<IllegalArgumentException> { call("action" to "update", "id" to id, "old" to "missing", "new" to "x") }
        call("action" to "update", "id" to id, "title" to "Lisbon", "content" to "fresh")
        assertEquals("fresh" to "Lisbon", artifacts.get(id).let { it.content to it.info.title })
        assertEquals(1L, artifacts.get(id).info.created)

        assertTrue(id in call("action" to "list"))
        artifacts.delete(id)
        assertEquals(emptyList(), artifacts.list())
        assertEquals(listOf(id, id, id, id), changes.map { it.id })
        assertNull(changes.last().info)
    }

    @Test
    fun copiesFromTheWorkspaceAndChecksTypesAndIds() = runTest {
        val id = Json.parseToJsonElement(call("action" to "create", "title" to "Chart", "from_path" to "out/chart.html")).jsonObject.getValue("id").jsonPrimitive.content
        assertEquals("html" to "<svg/>", artifacts.get(id).let { it.info.type to it.content })
        assertFailsWith<IllegalArgumentException> { call("action" to "create", "title" to "X", "type" to "exe", "content" to "x") }
        val padded = call("action" to "create", "id" to "", "title" to "Padded", "type" to "", "content" to "", "from_path" to "out/chart.html", "old" to "", "new" to "")
        assertEquals("<svg/>", artifacts.get(Json.parseToJsonElement(padded).jsonObject.getValue("id").jsonPrimitive.content).content)
        assertFailsWith<IllegalStateException> { call("action" to "create", "title" to "Empty", "type" to "html", "content" to "") }
        assertFailsWith<IllegalStateException> { artifacts.get("../agents") }
    }

    @Test
    fun jsonContentKeepsItsOwnFile() = runTest {
        val id = artifacts.create("main", null, "Data", "json", """{"answer":42}""").id
        assertEquals("""{"answer":42}""", artifacts.get(id).content)
        assertEquals(listOf(id), artifacts.list().map { it.id })
        artifacts.delete(id)
        assertEquals(emptyList(), artifacts.list())
    }
}
