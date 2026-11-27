package kinuvo.server.artifacts

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.artifact.ArtifactBundle
import kinuvo.protocol.session.SessionId
import kinuvo.server.TestHome
import kinuvo.server.plugin.isDirectory
import kinuvo.server.plugin.isFile
import kinuvo.server.store.FileArtifacts
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArtifactBundlesTest {
    private val home = TestHome()
    private var now = 1L
    private val artifacts = Artifacts(FileArtifacts(home.files.artifacts)) { now++ }
    private val agent = AgentId("main")
    private val session = SessionId("s1")
    private val wasm = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)

    private val workspace = mapOf(
        "dist/index.html" to "<script src=app.js></script>".encodeToByteArray(),
        "dist/app.js" to "run()".encodeToByteArray(),
        "dist/assets/module.wasm" to wasm,
        "dist/assets/.DS_Store" to byteArrayOf(1),
        "dist/tool.exe" to byteArrayOf(2),
        "dist/.git/config" to byteArrayOf(3),
        "out/chart.html" to "<svg/>".encodeToByteArray(),
    )
    private val tool = ArtifactTool(home.agent, session, artifacts, object : WorkspaceFiles {
        override suspend fun text(path: String) = workspace[path]?.decodeToString() ?: error("missing $path")
        override suspend fun bytes(path: String, maxBytes: Int) = workspace[path] ?: error("missing $path")
        override suspend fun list(path: String, limit: Int) = workspace.keys.filter { it.startsWith("${path.trimEnd('/')}/") }.map { it.removePrefix("${path.trimEnd('/')}/") }
    })

    private suspend fun call(vararg args: Pair<String, String>) = tool.execute(JsonObject(args.associate { (k, v) -> k to JsonPrimitive(v) }))
    private fun idOf(result: String) = Json.parseToJsonElement(result).jsonObject.getValue("id").jsonPrimitive.content

    private suspend fun bundle(vararg files: Pair<String, String>) =
        artifacts.createBundle(agent, session, "App", (mapOf("index.html" to "<p>home</p>") + files).mapValues { it.value.encodeToByteArray() }).id

    @Test
    fun aSingleFileIsAOneFileBundleAndStaysInPlace() = runTest {
        val info = artifacts.create(agent, session, "Page", "html", "<p>a</p>")
        assertTrue(Path(home.files.artifacts, "${info.id}.html").isFile())
        assertEquals(false, info.bundle)
        val files = artifacts.files(info.id)
        assertEquals("index.html", files.entry)
        assertEquals(listOf("index.html" to "text/html"), files.files.map { it.path to it.mime })
        assertEquals(8L, files.files.single().size)
        assertEquals("<p>a</p>", Base64.decode(artifacts.readFile(info.id, "index.html").data).decodeToString())
        assertFailsWith<IllegalStateException> { artifacts.readFile(info.id, "other.js") }
        assertFailsWith<IllegalArgumentException> { artifacts.deleteFile(info.id, agent, session, "index.html") }
    }

    @Test
    fun writingAFilePromotesASingleFileToAFolder() = runTest {
        val changes = mutableListOf<String>()
        artifacts.onChange { changes += it.id }
        val info = artifacts.create(agent, session, "Page", "html", "<script src=app.js></script>")
        val saved = artifacts.writeFiles(info.id, AgentId("other"), null, mapOf("app.js" to "run()".encodeToByteArray()))
        assertTrue(saved.bundle)
        assertEquals(AgentId("other"), saved.agentId)
        assertTrue(Path(home.files.artifacts, info.id).isDirectory())
        assertEquals(false, Path(home.files.artifacts, "${info.id}.html").isFile())
        assertEquals("<script src=app.js></script>", artifacts.get(info.id).content)
        assertEquals(listOf("app.js", "index.html"), artifacts.files(info.id).files.map { it.path })
        assertTrue(artifacts.list().single().bundle)
        assertEquals(listOf(info.id, info.id), changes)
    }

    @Test
    fun filesListWithMimeSizeAndHashThatChangesWithContent() = runTest {
        val id = artifacts.createBundle(agent, session, "App", mapOf("index.html" to "<p/>".encodeToByteArray(), "m/a.wasm" to wasm, "d.sqlite" to byteArrayOf(1, 2), "f.woff2" to byteArrayOf(9))).id
        val files = artifacts.files(id).files.associateBy { it.path }
        assertEquals(setOf("index.html", "m/a.wasm", "d.sqlite", "f.woff2"), files.keys)
        assertEquals("application/wasm", files.getValue("m/a.wasm").mime)
        assertEquals("application/vnd.sqlite3", files.getValue("d.sqlite").mime)
        assertEquals("font/woff2", files.getValue("f.woff2").mime)
        assertEquals(wasm.size.toLong(), files.getValue("m/a.wasm").size)
        val single = artifacts.create(agent, session, "Page", "html", "<p/>")
        assertEquals(artifacts.files(single.id).files.single().hash, files.getValue("index.html").hash)
        val before = files.getValue("d.sqlite").hash
        assertEquals(64, before.length)
        artifacts.writeFiles(id, agent, session, mapOf("d.sqlite" to byteArrayOf(1, 3)))
        assertNotEquals(before, artifacts.files(id).files.first { it.path == "d.sqlite" }.hash)
    }

    @Test
    fun readsAFileInChunks() = runTest {
        val big = ByteArray(ArtifactBundle.CHUNK_BYTES * 2 + 5) { (it % 251).toByte() }
        val id = artifacts.createBundle(agent, session, "App", mapOf("index.html" to "<p/>".encodeToByteArray(), "big.bin" to big)).id
        val read = mutableListOf<Byte>()
        var offset = 0L
        while (true) {
            val chunk = artifacts.readFile(id, "big.bin", offset)
            assertEquals(big.size.toLong(), chunk.size)
            val bytes = Base64.decode(chunk.data)
            if (bytes.isEmpty()) break
            assertTrue(bytes.size <= ArtifactBundle.CHUNK_BYTES)
            read += bytes.toList()
            offset += bytes.size
        }
        assertEquals(big.toList(), read)
        assertEquals(3, artifacts.readFile(id, "big.bin", 0, 3).let { Base64.decode(it.data).size })
        assertFailsWith<IllegalStateException> { artifacts.readFile(id, "nope.bin") }
        assertFailsWith<IllegalStateException> { artifacts.readFile(id, "../agents.json") }
    }

    @Test
    fun pathsMustStayInsideTheFolder() = runTest {
        val id = bundle()
        for (bad in listOf("../x.js", "/etc/x.js", "a/../../x.js", ".hidden.js", "a/.b/c.js", "a//b.js", "a\\b.js", "./x.js", "x.js/", "", "C:x.js", "a".repeat(201) + ".js", "a/b/c/d/e/f/g/h/i.js")) {
            assertFailsWith<IllegalArgumentException>(bad) { artifacts.writeFiles(id, agent, session, mapOf(bad to byteArrayOf(1))) }
        }
        assertFailsWith<IllegalArgumentException> { artifacts.createBundle(agent, session, "Bad", mapOf("../index.html" to byteArrayOf(1))) }
        assertEquals(listOf("index.html"), artifacts.files(id).files.map { it.path })
        assertFailsWith<IllegalStateException> { artifacts.readFile(id, "../../x") }
        assertFailsWith<IllegalArgumentException> { artifacts.fileBytes(id, "../meta.json") }
    }

    @Test
    fun onlyAllowedTypesAndLimitsAreAccepted() = runTest {
        val id = bundle()
        assertFailsWith<IllegalArgumentException> { artifacts.writeFiles(id, agent, session, mapOf("tool.exe" to byteArrayOf(1))) }
        assertFailsWith<IllegalArgumentException> { artifacts.writeFiles(id, agent, session, mapOf("noext" to byteArrayOf(1))) }
        assertFailsWith<IllegalArgumentException> { artifacts.writeFiles(id, agent, session, mapOf("big.bin" to ByteArray(ArtifactBundle.MAX_FILE_BYTES + 1))) }
        artifacts.writeFiles(id, agent, session, mapOf("max.bin" to ByteArray(ArtifactBundle.MAX_FILE_BYTES)))

        assertFailsWith<IllegalArgumentException> { artifacts.writeFiles(id, agent, session, mapOf("A.js" to byteArrayOf(1), "a.JS" to byteArrayOf(1))) }
        val many = (1..ArtifactBundle.MAX_FILES).associate { "f$it.txt" to byteArrayOf(1) }
        assertFailsWith<IllegalArgumentException> { artifacts.writeFiles(id, agent, session, many) }
        assertFailsWith<IllegalArgumentException> { artifacts.createBundle(agent, session, "Many", many + ("index.html" to byteArrayOf(1))) }
        val fill = (1..4).associate { "m$it.bin" to ByteArray(ArtifactBundle.MAX_FILE_BYTES) }
        assertFailsWith<IllegalArgumentException> { artifacts.writeFiles(id, agent, session, fill) }
        assertEquals(listOf("index.html", "max.bin"), artifacts.files(id).files.map { it.path })
    }

    @Test
    fun theManifestIsParsedAndPicksTheEntryPage() = runTest {
        val id = bundle()
        assertEquals(null, artifacts.files(id).manifest)
        assertFailsWith<IllegalArgumentException> { artifacts.writeFiles(id, agent, session, mapOf("manifest.json" to "nope".encodeToByteArray())) }
        assertFailsWith<IllegalArgumentException> { artifacts.writeFiles(id, agent, session, mapOf("manifest.json" to """{"entry":"../x.html"}""".encodeToByteArray())) }
        assertFailsWith<IllegalArgumentException> { artifacts.writeFiles(id, agent, session, mapOf("manifest.json" to """{"entry":"app.js"}""".encodeToByteArray())) }

        artifacts.writeFiles(id, agent, session, mapOf("app.html" to "<p>app</p>".encodeToByteArray(), "manifest.json" to """{"entry":"app.html","title":"My app","capabilities":{"network":["https://api.example.com"],"popups":true,"future":1},"extra":2}""".encodeToByteArray()))
        val files = artifacts.files(id)
        assertEquals("app.html", files.entry)
        assertEquals("My app", files.manifest?.title)
        assertEquals(listOf("https://api.example.com"), files.manifest?.capabilities?.network)
        assertEquals(true, files.manifest?.capabilities?.popups)
        assertEquals("<p>app</p>", artifacts.get(id).content)

        artifacts.replace(id, "<p>edited</p>")
        assertEquals("<p>edited</p>", Base64.decode(artifacts.readFile(id, "app.html").data).decodeToString())
        assertEquals("<p>home</p>", Base64.decode(artifacts.readFile(id, "index.html").data).decodeToString())
        artifacts.update(id, agent, session) { it.replace("edited", "again") }
        assertEquals("<p>again</p>", artifacts.get(id).content)
        assertFailsWith<IllegalArgumentException> { artifacts.deleteFile(id, agent, session, "app.html") }
    }

    @Test
    fun aFolderNeedsItsEntryPage() = runTest {
        assertFailsWith<IllegalArgumentException> { artifacts.createBundle(agent, session, "No entry", mapOf("app.js" to byteArrayOf(1))) }
        assertFailsWith<IllegalArgumentException> { artifacts.createBundle(agent, session, "Entry elsewhere", mapOf("index.html" to byteArrayOf(1), "manifest.json" to """{"entry":"main.html"}""".encodeToByteArray())) }
        assertEquals(emptyList(), artifacts.list())
    }

    @Test
    fun deletingFilesPrunesEmptyFoldersAndDeletingTheArtifactRemovesTheFolder() = runTest {
        val id = bundle("a/b/c.js" to "x")
        assertTrue(Path(home.files.artifacts, id, "a", "b").isDirectory())
        artifacts.deleteFile(id, agent, session, "a/b/c.js")
        assertEquals(false, Path(home.files.artifacts, id, "a").isDirectory())
        assertFailsWith<IllegalStateException> { artifacts.deleteFile(id, agent, session, "a/b/c.js") }
        artifacts.delete(id)
        assertEquals(false, Path(home.files.artifacts, id).isDirectory())
        assertEquals(emptyList(), artifacts.list())
    }

    @Test
    fun theToolCopiesAWorkspaceFolderAsABundle() = runTest {
        val result = call("action" to "create", "title" to "Deck", "from_path" to "dist")
        val id = idOf(result)
        val json = Json.parseToJsonElement(result).jsonObject
        assertTrue(json.getValue("folder").jsonPrimitive.boolean)
        assertEquals(listOf("tool.exe"), json.getValue("skipped").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("app.js", "assets/module.wasm", "index.html"), artifacts.files(id).files.map { it.path })
        assertEquals("application/wasm", artifacts.files(id).files.first { it.path == "assets/module.wasm" }.mime)
        assertEquals(wasm.toList(), artifacts.fileBytes(id, "assets/module.wasm").toList())
        assertEquals("html", artifacts.get(id).info.type)
    }

    @Test
    fun theToolWritesEditsListsReadsAndDeletesFiles() = runTest {
        val id = idOf(call("action" to "create", "title" to "App", "type" to "html", "content" to "<script src=app.js></script>"))
        call("action" to "file_write", "id" to id, "path" to "app.js", "content" to "let a = 1")
        call("action" to "file_edit", "id" to id, "path" to "app.js", "old" to "1", "new" to "2")
        assertEquals("let a = 2", call("action" to "file_read", "id" to id, "path" to "app.js"))
        assertFailsWith<IllegalArgumentException> { call("action" to "file_edit", "id" to id, "path" to "app.js", "old" to "missing", "new" to "x") }

        call("action" to "file_write", "id" to id, "path" to "m.wasm", "from_path" to "dist/assets/module.wasm")
        assertTrue("m.wasm · ${wasm.size} bytes · application/wasm" in call("action" to "files", "id" to id))
        assertTrue("entry: index.html" in call("action" to "files", "id" to id))
        assertEquals("${wasm.size} bytes of application/wasm, not text", call("action" to "file_read", "id" to id, "path" to "m.wasm"))

        call("action" to "file_write", "id" to id, "path" to "static", "from_path" to "dist")
        assertEquals(listOf("app.js", "index.html", "m.wasm", "static/app.js", "static/assets/module.wasm", "static/index.html"), artifacts.files(id).files.map { it.path })

        call("action" to "file_write", "id" to id, "path" to "manifest.json", "content" to """{"capabilities":{"popups":true}}""")
        assertTrue("manifest:" in call("action" to "files", "id" to id))
        call("action" to "update", "id" to id, "old" to "app.js", "new" to "static/app.js")
        assertEquals("<script src=static/app.js></script>", artifacts.get(id).content)

        call("action" to "file_delete", "id" to id, "path" to "m.wasm")
        assertFailsWith<IllegalArgumentException> { call("action" to "file_delete", "id" to id, "path" to "index.html") }
        assertFailsWith<IllegalStateException> { call("action" to "file_write", "id" to id, "content" to "x") }
        assertFailsWith<IllegalArgumentException> { call("action" to "file_write", "id" to id, "path" to "../x.js", "content" to "x") }
        assertTrue(artifacts.list().single().bundle)
    }

    @Test
    fun onlyHtmlArtifactsTakeFiles() = runTest {
        val md = artifacts.create(agent, session, "Notes", "md", "# hi")
        assertFailsWith<IllegalArgumentException> { artifacts.writeFiles(md.id, agent, session, mapOf("a.js" to byteArrayOf(1))) }
    }
}
