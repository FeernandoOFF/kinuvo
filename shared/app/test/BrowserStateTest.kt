package kinuvo.app

import kinuvo.app.browser.BrowserState
import kinuvo.protocol.*
import kotlin.test.*

class BrowserStateTest {
    private fun tab(id: String) = BrowserTab(id, "agent", "session", id, "https://example.test")
    @Test fun selectionSurvivesRefreshAndRemovedTabsDropTheirFrame() {
        val first = tab("one")
        val second = tab("two")
        var state = BrowserState().listed(listOf(first, second))
        assertEquals("one", state.selected)
        state = state.copy(selected = "two", frame = BrowserFrame(second, "capture"))
        assertEquals("two", state.listed(listOf(first, second)).selected)
        state = state.listed(listOf(first))
        assertEquals("one", state.selected)
        assertNull(state.frame)
        state = state.listed(emptyList())
        assertNull(state.selected)
        assertTrue(state.loaded)
    }
}
