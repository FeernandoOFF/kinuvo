package kinuvo.server.artifacts

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.artifact.ArtifactCapabilities
import kinuvo.protocol.artifact.ArtifactChange
import kinuvo.protocol.artifact.ArtifactGrantChange
import kinuvo.protocol.session.SessionId
import kinuvo.server.TestHome
import kinuvo.server.store.FileArtifacts
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactGrantsTest {
    private val home = TestHome()
    private var now = 1L
    private val repo = FileArtifacts(home.files.artifacts)
    private val artifacts = Artifacts(repo) { now++ }
    private val agent = AgentId("main")
    private val session = SessionId("s1")

    private fun manifest(network: String = "\"https://api.example.com\",\"wss://api.example.com\"", popups: Boolean = true, storage: Boolean = false, tools: String = "") =
        """{"capabilities":{"network":[$network],"popups":$popups,"storage":$storage,"tools":[$tools]}}"""

    private suspend fun app(manifest: String = manifest()) =
        artifacts.createBundle(agent, session, "App", mapOf("index.html" to "<p/>".encodeToByteArray(), "manifest.json" to manifest.encodeToByteArray())).id

    private suspend fun write(id: String, manifest: String) = artifacts.writeFiles(id, agent, session, mapOf("manifest.json" to manifest.encodeToByteArray()))

    @Test
    fun nothingIsGrantedByDefault() = runTest {
        val permissions = artifacts.permissions(app())
        assertEquals(listOf("https://api.example.com", "wss://api.example.com"), permissions.requested.network)
        assertTrue(permissions.requested.popups)
        assertEquals(ArtifactCapabilities(), permissions.granted)
        assertEquals(ArtifactCapabilities(), permissions.effective)
        assertTrue(permissions.pending)
        assertNull(permissions.at)
    }

    @Test
    fun anArtifactWithoutAManifestOrAsAFileAsksForNothing() = runTest {
        val folder = artifacts.createBundle(agent, session, "Plain", mapOf("index.html" to "<p/>".encodeToByteArray())).id
        val single = artifacts.create(agent, session, "Page", "html", "<p/>").id
        listOf(folder, single).forEach { id ->
            val permissions = artifacts.permissions(id)
            assertFalse(permissions.asking)
            assertFalse(permissions.pending)
        }
        assertFailsWith<IllegalArgumentException> { artifacts.changeGrants(single, ArtifactGrantChange(single, grant = ArtifactCapabilities(popups = true)), null, null) }
    }

    @Test
    fun grantingOneCapabilityAppliesOnlyThatOne() = runTest {
        val id = app()
        val permissions = artifacts.changeGrants(id, ArtifactGrantChange(id, grant = ArtifactCapabilities(network = listOf("HTTPS://API.example.com/"))), "phone", "Phone")
        assertEquals(listOf("https://api.example.com"), permissions.effective.network)
        assertFalse(permissions.effective.popups)
        assertTrue(permissions.pending)
        assertEquals(listOf("phone", "Phone"), listOf(permissions.by, permissions.byName))
        assertTrue(artifacts.permissions(id).at != null)
    }

    @Test
    fun grantAllGrantsWhatIsAskedForAndNothingMore() = runTest {
        val id = app(manifest(storage = true, tools = "\"search\""))
        val permissions = artifacts.changeGrants(id, ArtifactGrantChange(id, all = true), "d", "D")
        assertEquals(permissions.requested, permissions.effective)
        assertTrue(permissions.everything)
        assertFalse(permissions.pending)
        assertEquals(listOf("search"), permissions.effective.tools)
    }

    @Test
    fun revokingOneOrAllTakesGrantsBack() = runTest {
        val id = app()
        artifacts.changeGrants(id, ArtifactGrantChange(id, all = true), "d", "D")
        val partial = artifacts.changeGrants(id, ArtifactGrantChange(id, revoke = ArtifactCapabilities(network = listOf("wss://api.example.com"), popups = true)), "d", "D")
        assertEquals(listOf("https://api.example.com"), partial.effective.network)
        assertFalse(partial.effective.popups)
        assertEquals(ArtifactCapabilities(), artifacts.changeGrants(id, ArtifactGrantChange(id, all = false), "d", "D").granted)
        assertNull(repo.grants(id))
    }

    @Test
    fun aGrantCannotExceedTheRequest() = runTest {
        val id = app()
        assertFailsWith<IllegalArgumentException> { artifacts.changeGrants(id, ArtifactGrantChange(id, grant = ArtifactCapabilities(network = listOf("https://evil.example.net"))), null, null) }
        assertFailsWith<IllegalArgumentException> { artifacts.changeGrants(id, ArtifactGrantChange(id, grant = ArtifactCapabilities(storage = true)), null, null) }
        assertFailsWith<IllegalArgumentException> { artifacts.changeGrants(id, ArtifactGrantChange(id, grant = ArtifactCapabilities(tools = listOf("run"))), null, null) }
        assertEquals(ArtifactCapabilities(), artifacts.permissions(id).granted)
    }

    @Test
    fun grantsSurviveAReload() = runTest {
        val id = app()
        artifacts.changeGrants(id, ArtifactGrantChange(id, all = true), "phone", "Phone")
        val reopened = Artifacts(FileArtifacts(home.files.artifacts)) { now++ }.permissions(id)
        assertEquals(reopened.requested, reopened.effective)
        assertEquals("phone", reopened.by)
        assertEquals("Phone", reopened.byName)
        assertTrue(reopened.at != null)
    }

    @Test
    fun changingTheRequestsNeedsANewGrant() = runTest {
        val id = app()
        artifacts.changeGrants(id, ArtifactGrantChange(id, all = true), "d", "D")
        write(id, manifest(network = "\"https://api.example.com\",\"https://other.example.com\""))
        val widened = artifacts.permissions(id)
        assertEquals(listOf("https://api.example.com"), widened.effective.network)
        assertTrue(widened.effective.popups)
        assertTrue(widened.pending)

        write(id, manifest(network = "\"https://api.example.com\""))
        val back = artifacts.permissions(id)
        assertEquals(listOf("https://api.example.com"), back.granted.network)

        write(id, manifest(network = "\"https://api.example.com\",\"https://other.example.com\""))
        assertEquals(listOf("https://api.example.com"), artifacts.permissions(id).effective.network)
        assertTrue(artifacts.permissions(id).pending)
    }

    @Test
    fun removingTheManifestDropsEveryGrant() = runTest {
        val id = app()
        artifacts.changeGrants(id, ArtifactGrantChange(id, all = true), "d", "D")
        artifacts.deleteFile(id, agent, session, "manifest.json")
        assertEquals(ArtifactCapabilities(), artifacts.permissions(id).granted)
        write(id, manifest())
        assertEquals(ArtifactCapabilities(), artifacts.permissions(id).effective)
    }

    @Test
    fun deletingTheArtifactClearsItsGrants() = runTest {
        val id = app()
        artifacts.changeGrants(id, ArtifactGrantChange(id, all = true), "d", "D")
        assertTrue(repo.grants(id) != null)
        artifacts.delete(id)
        assertNull(repo.grants(id))
    }

    @Test
    fun changingGrantsTellsEveryDevice() = runTest {
        val id = app()
        val changes = mutableListOf<ArtifactChange>()
        artifacts.onChange { changes += it }
        artifacts.changeGrants(id, ArtifactGrantChange(id, all = true), "d", "D")
        assertEquals(listOf(id), changes.map { it.id })
    }

    @Test
    fun aManifestWithAnOriginAnArtifactMayNotReachIsRefused() = runTest {
        listOf("http://api.example.com", "ws://api.example.com", "https://localhost", "*", "https://*", "https://*.com", "https://a.example.com/path", "https://127.0.0.1").forEach { origin ->
            assertFailsWith<IllegalArgumentException>(origin) { app(manifest(network = "\"$origin\"")) }
        }
        val id = app()
        assertFailsWith<IllegalArgumentException> { write(id, manifest(network = "\"https://*.com\"")) }
        assertEquals(listOf("https://api.example.com", "wss://api.example.com"), artifacts.permissions(id).requested.network)
    }

    @Test
    fun wildcardsAndPortsAreKeptAsAsked() = runTest {
        val id = app(manifest(network = "\"wss://*.example.com\",\"https://api.example.com:8443\""))
        assertEquals(listOf("wss://*.example.com", "https://api.example.com:8443"), artifacts.permissions(id).requested.network)
    }
}
