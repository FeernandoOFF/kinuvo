package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.Model
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionDetailsTest {
    private val home = Files.createTempDirectory("harness-details").toFile()

    @Test
    fun renamesAndFavoritesStayOutOfTheModelsHistory(): Unit = runBlocking {
        val seen = mutableListOf<List<SessionEntry>>()
        val harness = Harness(Path(home.path), modelFactory = { Model { _, history, _ -> seen += history; flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End()) } }, plugins = emptyList())
        try {
            val agent = harness.store.agents().single()
            val session = harness.newSession(agent).id
            harness.runner(agent, session).run(session, "first message").toList()
            val older = harness.newSession(agent).id
            harness.runner(agent, older).run(older, "other").toList()

            assertEquals("Trip plans", harness.update(agent, session, "  Trip plans ", null).title)
            assertTrue(harness.update(agent, session, null, true).let { it.favorite && it.title == "Trip plans" })
            assertEquals(listOf(session), harness.log.page(agent, null, 1).favorites.map { it.id })
            assertEquals(emptyList(), harness.log.page(agent, older, 1).favorites)

            harness.runner(agent, session).run(session, "again").toList()
            assertTrue(seen.last().none { it is SessionEntry.Details })

            val reset = harness.update(agent, session, " ", false)
            assertEquals("first message", reset.title)
            assertFalse(reset.favorite)
        } finally { harness.close() }
    }
}
