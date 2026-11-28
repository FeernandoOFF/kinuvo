package kinuvo.testing.server

import kinuvo.protocol.Api
import kinuvo.protocol.artifact.ArtifactRef
import kinuvo.protocol.artifact.ArtifactState
import kinuvo.protocol.artifact.ArtifactStateSet
import kinuvo.protocol.agent.AgentId
import kinuvo.testing.harness.awaitCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArtifactStateTest {
    private fun slide(h: Int) = buildJsonObject { put("indexh", h) }

    @Test
    fun aDeviceWatchingAnArtifactFollowsAnother() = runTest {
        TestServer.start().use { server ->
            withContext(Dispatchers.Default) {
                val deck = server.harness.artifacts.create(AgentId("main"), null, "Deck", "html", "<p/>")
                val phone = server.client("phone").also { it.connect() }
                val desktop = server.client("desktop").also { it.connect() }
                val scope = CoroutineScope(Dispatchers.Default)
                val onDesktop = mutableListOf<ArtifactState>()
                val onPhone = mutableListOf<ArtifactState>()
                desktop.events(Api.ArtifactStateChanged).onEach { onDesktop += it }.launchIn(scope)
                phone.events(Api.ArtifactStateChanged).onEach { onPhone += it }.launchIn(scope)

                assertEquals(ArtifactState(deck.id), desktop.call(Api.WatchArtifactState, ArtifactRef(deck.id)))
                assertFailsWith<Exception> { phone.call(Api.SetArtifactState, ArtifactStateSet(deck.id, slide(9))) }
                assertEquals(ArtifactState(deck.id), phone.call(Api.WatchArtifactState, ArtifactRef(deck.id)))

                phone.call(Api.SetArtifactState, ArtifactStateSet(deck.id, slide(1)))
                phone.call(Api.SetArtifactState, ArtifactStateSet(deck.id, slide(2)))
                awaitCondition("the desktop to get both changes") { onDesktop.size == 2 }
                assertEquals(listOf(slide(1), slide(2)), onDesktop.map { it.state })
                assertEquals(emptyList(), onPhone)

                val late = server.client("tablet").also { it.connect() }
                assertEquals(ArtifactState(deck.id, slide(2)), late.call(Api.WatchArtifactState, ArtifactRef(deck.id)))

                desktop.call(Api.SetArtifactState, ArtifactStateSet(deck.id, slide(3)))
                awaitCondition("the phone to follow") { onPhone.size == 1 }
                assertEquals(slide(3), onPhone.single().state)
                assertEquals(2, onDesktop.size)
            }
        }
    }

    @Test
    fun aClosedConnectionStopsWatchingAndDeletingForgetsTheState() = runTest {
        TestServer.start().use { server ->
            withContext(Dispatchers.Default) {
                val deck = server.harness.artifacts.create(AgentId("main"), null, "Deck", "html", "<p/>")
                val phone = server.client("phone").also { it.connect() }
                val desktop = server.client("desktop").also { it.connect() }
                phone.call(Api.WatchArtifactState, ArtifactRef(deck.id))
                desktop.call(Api.WatchArtifactState, ArtifactRef(deck.id))
                phone.call(Api.SetArtifactState, ArtifactStateSet(deck.id, slide(1)))
                desktop.close()
                awaitCondition("the Server to drop the desktop") { server.harness.artifacts.states.watchers(deck.id) == 1 }
                server.harness.artifacts.delete(deck.id)
                assertFailsWith<Exception> { phone.call(Api.WatchArtifactState, ArtifactRef(deck.id)) }
                assertEquals(null, server.harness.artifacts.states.get(deck.id))
            }
        }
    }
}
