package kinuvo.app

import kinuvo.app.session.*

import kinuvo.app.session.Completion
import kinuvo.app.session.CompletionSource
import kinuvo.app.session.findCompletion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompletionsTest {
    private val skills = CompletionSource('/', listOf(Completion("review"), Completion("pr-review"), Completion("plan")), startOnly = true)
    private val tags = CompletionSource('@', listOf(Completion("ana")))

    private fun find(text: String, cursor: Int = text.length) = findCompletion(text, cursor, cursor, listOf(skills, tags))

    @Test
    fun matchesTriggerWordAtCursorPrefixFirst() {
        val q = find("/rev")!!
        assertEquals(listOf("review", "pr-review"), q.matches.map { it.value })
        assertEquals(0 to 4, q.start to q.end)
        assertEquals(listOf("review", "pr-review", "plan"), find("/")!!.matches.map { it.value })
    }

    @Test
    fun startOnlySourcesIgnoreLaterWords() {
        assertNull(find("hi /rev"))
        assertEquals("rev", find("  /rev")!!.query)
        assertEquals(3, find("hi @a")!!.start)
    }

    @Test
    fun replacesWholeWordAndStopsAtWhitespace() {
        assertEquals(0 to 6, find("/revxx later", 3)!!.let { it.start to it.end })
        assertNull(find("/review "))
        assertNull(findCompletion("/rev", 0, 4, listOf(skills)))
    }
}
