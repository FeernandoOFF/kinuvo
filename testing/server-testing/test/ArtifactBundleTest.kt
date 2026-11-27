package kinuvo.testing.server

import kinuvo.protocol.Api
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.artifact.ArtifactBundle
import kinuvo.protocol.artifact.ArtifactFileRef
import kinuvo.protocol.artifact.ArtifactInfo
import kinuvo.protocol.artifact.ArtifactRef
import kinuvo.protocol.artifact.ArtifactSave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactBundleTest {
    private val wasm = ByteArray(ArtifactBundle.CHUNK_BYTES + 100) { it.toByte() }

    @Test
    fun aDeviceListsAndReadsTheFilesOfAFolder() = runTest {
        TestServer.start().use { server ->
            withContext(Dispatchers.Default) {
                val bundle = server.harness.artifacts.createBundle(AgentId("main"), null, "App", mapOf(
                    "index.html" to "<script src=app.js></script>".encodeToByteArray(),
                    "app.js" to "run()".encodeToByteArray(),
                    "wasm/m.wasm" to wasm,
                ))
                val single = server.harness.artifacts.create(AgentId("main"), null, "Page", "html", "<p/>")
                val phone = server.client("phone").also { it.connect() }

                val listed = phone.call(Api.Artifacts, Unit).associateBy { it.id }
                assertTrue(listed.getValue(bundle.id).bundle)
                assertFalse(listed.getValue(single.id).bundle)
                assertEquals("<script src=app.js></script>", phone.call(Api.Artifact, ArtifactRef(bundle.id)).content)

                val files = phone.call(Api.ArtifactFileList, ArtifactRef(bundle.id))
                assertEquals("index.html", files.entry)
                assertEquals(listOf("app.js", "index.html", "wasm/m.wasm"), files.files.map { it.path })
                assertEquals("application/wasm", files.files.single { it.path == "wasm/m.wasm" }.mime)

                val bytes = mutableListOf<Byte>()
                while (bytes.size < wasm.size) {
                    val chunk = phone.call(Api.ArtifactFileRead, ArtifactFileRef(bundle.id, "wasm/m.wasm", bytes.size.toLong()))
                    assertEquals(wasm.size.toLong(), chunk.size)
                    bytes += Base64.decode(chunk.data).toList()
                }
                assertEquals(wasm.toList(), bytes)

                assertEquals(listOf("index.html"), phone.call(Api.ArtifactFileList, ArtifactRef(single.id)).files.map { it.path })
                assertFailsWith<Exception> { phone.call(Api.ArtifactFileRead, ArtifactFileRef(bundle.id, "../agents.json")) }

                phone.call(Api.SaveArtifact, ArtifactSave(bundle.id, "<p>edited</p>"))
                assertEquals("<p>edited</p>", phone.call(Api.Artifact, ArtifactRef(bundle.id)).content)
                assertEquals(listOf("app.js", "index.html", "wasm/m.wasm"), phone.call(Api.ArtifactFileList, ArtifactRef(bundle.id)).files.map { it.path })
            }
        }
    }

    @Test
    fun anOlderPeersArtifactInfoReadsAsASingleFile() {
        val info = ProtocolJson.decodeFromString(ArtifactInfo.serializer(), """{"id":"a","title":"A","type":"html","agentId":"main","created":1,"updated":2,"unknown":true}""")
        assertFalse(info.bundle)
    }
}
