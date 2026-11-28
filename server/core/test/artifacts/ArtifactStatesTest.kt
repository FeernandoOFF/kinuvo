package kinuvo.server.artifacts

import kinuvo.protocol.artifact.ArtifactState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactStatesTest {
    private val states = ArtifactStates()
    private val presenter = Any()
    private val audience = Any()
    private val seen = mutableMapOf<Any, MutableList<ArtifactState>>()

    private fun watch(who: Any, id: String = "deck") = states.watch(id, who) { seen.getOrPut(who) { mutableListOf() } += it }
    private fun slide(h: Int) = buildJsonObject { put("indexh", h) }

    @Test
    fun setReplacesAndGetReturnsTheLatest() {
        assertNull(states.get("deck"))
        watch(presenter)
        states.set("deck", presenter, slide(1))
        states.set("deck", presenter, slide(2))
        assertEquals(slide(2), states.get("deck"))
        assertNull(states.get("other"))
    }

    @Test
    fun otherWatchersGetEachChangeInOrderAndTheSetterNone() {
        watch(presenter)
        watch(audience)
        states.set("deck", presenter, slide(1))
        states.set("deck", presenter, slide(2))
        assertEquals<List<ArtifactState>?>(listOf(ArtifactState("deck", slide(1)), ArtifactState("deck", slide(2))), seen[audience])
        assertNull(seen[presenter])
    }

    @Test
    fun aLateWatcherStartsFromTheLatestState() {
        watch(presenter)
        states.set("deck", presenter, slide(4))
        assertEquals(slide(4), watch(audience))
        assertNull(seen[audience])
    }

    @Test
    fun stateIsPerArtifact() {
        watch(presenter, "a")
        watch(audience, "b")
        states.set("a", presenter, slide(1))
        assertNull(seen[audience])
        assertNull(states.get("b"))
    }

    @Test
    fun unwatchingAndDisconnectingStopDelivery() {
        watch(presenter)
        watch(audience)
        states.unwatch("deck", audience)
        states.set("deck", presenter, slide(1))
        assertNull(seen[audience])
        assertFalse(states.watching("deck", audience))

        val other = Any()
        watch(other)
        watch(other, "second")
        states.disconnect(other)
        assertFalse(states.watching("deck", other) || states.watching("second", other))
        states.set("deck", presenter, slide(2))
        assertNull(seen[other])
        assertTrue(states.watching("deck", presenter))
    }

    @Test
    fun aFailingWatcherDoesNotStopTheOthers() {
        val broken = Any()
        states.watch("deck", broken) { error("closed") }
        watch(audience)
        watch(presenter)
        states.set("deck", presenter, slide(1))
        assertEquals(1, seen[audience]?.size)
    }

    @Test
    fun aWatcherMayUnwatchWhileBeingDelivered() {
        states.watch("deck", audience) { states.disconnect(audience) }
        watch(presenter)
        states.set("deck", presenter, slide(1))
        assertFalse(states.watching("deck", audience))
    }

    @Test
    fun clearForgetsStateAndWatchers() {
        watch(presenter)
        states.set("deck", audience, slide(1))
        states.clear("deck")
        assertNull(states.get("deck"))
        assertFalse(states.watching("deck", presenter))
    }

    @Test
    fun oversizedStateIsRefused() {
        val big = JsonArray(List(ArtifactStates.MAX_CHARS) { JsonPrimitive(1) })
        assertFailsWith<IllegalArgumentException> { states.set("deck", presenter, big) }
        assertNull(states.get("deck"))
    }
}
