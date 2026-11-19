package kinuvo.app

import kinuvo.app.panel.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlin.test.*

class SidePanelTest {
    private val echo = PanelTab.Browser(AgentId("echo"))

    @Test fun openingShowsTheTabOnceAndExpands() {
        val panel = SidePanelViewModel()
        panel.collapse(true)
        panel.open(echo)
        panel.open(PanelTab.Artifact("a"))
        panel.open(PanelTab.Browser(AgentId("echo"), SessionId("s")))
        val s = panel.state.value
        assertEquals(listOf(PanelTab.Browser(AgentId("echo"), SessionId("s")), PanelTab.Artifact("a")), s.tabs)
        assertEquals(SessionId("s"), (s.tabs[0] as PanelTab.Browser).sessionId)
        assertTrue(s.showing(echo))
    }

    @Test fun closingTheShownTabShowsItsNeighbour() {
        val panel = SidePanelViewModel()
        listOf(PanelTab.AgentSettings, echo, PanelTab.Computer("mac")).forEach(panel::open)
        panel.select(echo)
        panel.close(echo)
        assertEquals(PanelTab.Computer("mac"), panel.state.value.selected)
        panel.close(PanelTab.Computer("mac"))
        assertEquals(PanelTab.AgentSettings, panel.state.value.selected)
    }

    @Test fun togglingTheShownTabClosesItAndSelectingItCollapses() {
        val panel = SidePanelViewModel()
        panel.toggle(echo)
        assertTrue(panel.state.value.showing(echo))
        panel.select(echo)
        assertTrue(panel.state.value.collapsed)
        panel.toggle(echo)
        assertTrue(panel.state.value.showing(echo))
        panel.toggle(echo)
        assertEquals(emptyList(), panel.state.value.tabs)
    }

    @Test fun draggingNarrowCollapsesAndKeepsTheWidth() {
        val panel = SidePanelViewModel()
        panel.resize(900f, 700f)
        assertEquals(700f, panel.state.value.width)
        panel.resize(100f, 700f)
        assertTrue(panel.state.value.collapsed)
        assertEquals(700f, panel.state.value.width)
        panel.resize(300f, 700f)
        assertEquals(MinPanelWidth, panel.state.value.width)
        assertFalse(panel.state.value.collapsed)
    }
}
