package kinuvo.testing.server

import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.artifact.ArtifactCapabilities
import kinuvo.protocol.artifact.ArtifactChange
import kinuvo.protocol.artifact.ArtifactGrantChange
import kinuvo.protocol.artifact.ArtifactRef
import kinuvo.testing.harness.awaitCondition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactGrantsTest {
    private val manifest = """{"capabilities":{"network":["https://api.example.com","wss://api.example.com"],"popups":true}}"""

    @Test
    fun anOwnerGrantsAndEveryDeviceSeesWhatApplies() = runTest {
        TestServer.start().use { server ->
            withContext(Dispatchers.Default) {
                val app = server.harness.artifacts.createBundle(AgentId("main"), null, "App", mapOf("index.html" to "<p/>".encodeToByteArray(), "manifest.json" to manifest.encodeToByteArray()))
                val phone = server.client("phone").also { it.connect() }
                server.auth.pairing(refresh = true, management = false)
                val tablet = server.client("tablet").also { it.connect() }
                val changes = mutableListOf<ArtifactChange>()
                tablet.events(Api.ArtifactChanged).onEach { changes += it }.launchIn(CoroutineScope(Dispatchers.Default))

                val before = tablet.call(Api.ArtifactPermissionList, ArtifactRef(app.id))
                assertTrue(before.pending)
                assertEquals(ArtifactCapabilities(), before.effective)

                val granted = phone.call(Api.ChangeArtifactGrants, ArtifactGrantChange(app.id, grant = ArtifactCapabilities(network = listOf("wss://api.example.com"))))
                assertEquals(listOf("wss://api.example.com"), granted.effective.network)
                assertEquals("phone", granted.byName)
                awaitCondition("the tablet to hear of the change") { changes.any { it.id == app.id } }
                assertEquals(granted, tablet.call(Api.ArtifactPermissionList, ArtifactRef(app.id)))

                phone.call(Api.ChangeArtifactGrants, ArtifactGrantChange(app.id, all = true)).let { assertTrue(it.everything) }
                phone.call(Api.ChangeArtifactGrants, ArtifactGrantChange(app.id, revoke = ArtifactCapabilities(popups = true))).let { assertFalse(it.effective.popups) }
                assertFailsWith<Exception> { phone.call(Api.ChangeArtifactGrants, ArtifactGrantChange(app.id, grant = ArtifactCapabilities(network = listOf("https://other.example.com")))) }
            }
        }
    }

    @Test
    fun onlyManagementDevicesCanGrant() = runTest {
        TestServer.start().use { server ->
            withContext(Dispatchers.Default) {
                val app = server.harness.artifacts.createBundle(AgentId("main"), null, "App", mapOf("index.html" to "<p/>".encodeToByteArray(), "manifest.json" to manifest.encodeToByteArray()))
                server.client("phone").also { it.connect() }
                server.auth.pairing(refresh = true, management = false)
                val guest = server.client("guest").also { it.connect() }
                assertFailsWith<Exception> { guest.call(Api.ChangeArtifactGrants, ArtifactGrantChange(app.id, all = true)) }
                assertFalse(guest.call(Api.ArtifactPermissionList, ArtifactRef(app.id)).everything)
            }
        }
    }

    @Test
    fun anAgentsManifestEditNeedsANewGrantAndDeleteClearsIt() = runTest {
        TestServer.start().use { server ->
            withContext(Dispatchers.Default) {
                val artifacts = server.harness.artifacts
                val app = artifacts.createBundle(AgentId("main"), null, "App", mapOf("index.html" to "<p/>".encodeToByteArray(), "manifest.json" to manifest.encodeToByteArray()))
                val phone = server.client("phone").also { it.connect() }
                phone.call(Api.ChangeArtifactGrants, ArtifactGrantChange(app.id, all = true))
                artifacts.writeFiles(app.id, AgentId("main"), null, mapOf("manifest.json" to """{"capabilities":{"network":["https://evil.example.net"],"popups":true}}""".encodeToByteArray()))
                phone.call(Api.ArtifactPermissionList, ArtifactRef(app.id)).let {
                    assertTrue(it.pending)
                    assertEquals(emptyList(), it.effective.network)
                    assertTrue(it.effective.popups)
                }
                artifacts.delete(app.id)
                assertFailsWith<Exception> { phone.call(Api.ArtifactPermissionList, ArtifactRef(app.id)) }
            }
        }
    }
}
