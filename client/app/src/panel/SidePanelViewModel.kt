package kinuvo.app.panel

import androidx.lifecycle.ViewModel
import kinuvo.app.navigation.Route
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What a Side panel tab shows. One tab each for the Agent settings, an Agent's Browser, a Computer and an Artifact. */
sealed interface PanelTab {
    data object AgentSettings : PanelTab
    data class Browser(val agentId: AgentId, val sessionId: SessionId? = null) : PanelTab {
        override fun equals(other: Any?) = other is Browser && other.agentId == agentId
        override fun hashCode() = agentId.hashCode()
    }
    data class Computer(val computer: String) : PanelTab
    data class Artifact(val artifact: String) : PanelTab

    /** The full screen this tab expands to. */
    val route: Route? get() = when (this) {
        AgentSettings -> null
        is Browser -> Route.Browser(agentId, sessionId)
        is Computer -> Route.Computers(computer)
        is Artifact -> Route.Artifacts(artifact)
    }
}

/** [width] in dp is kept while [collapsed] leaves only the tab strip. */
data class SidePanelState(
    val tabs: List<PanelTab> = emptyList(),
    val selected: PanelTab? = null,
    val width: Float = DefaultPanelWidth,
    val collapsed: Boolean = false,
) {
    /** Whether [tab] is the one shown, with the panel expanded. */
    fun showing(tab: PanelTab) = !collapsed && selected == tab
}

const val DefaultPanelWidth = 480f
const val MinPanelWidth = 360f
/** Dragging the panel narrower than this collapses it. */
const val CollapsePanelWidth = 240f

/** The window's Side panel beside its screens on wide layouts: its tabs, the one shown, and its width. */
class SidePanelViewModel : ViewModel() {
    val state: StateFlow<SidePanelState>
        field = MutableStateFlow(SidePanelState())

    /** Adds [tab] if it isn't open, replacing an open tab equal to it, and shows it. */
    fun open(tab: PanelTab) = state.update { s ->
        val i = s.tabs.indexOf(tab)
        s.copy(tabs = if (i < 0) s.tabs + tab else s.tabs.toMutableList().also { it[i] = tab }, selected = tab, collapsed = false)
    }

    /** Shows [tab], or closes it when it's already shown. */
    fun toggle(tab: PanelTab) = if (state.value.showing(tab)) close(tab) else open(tab)

    fun select(tab: PanelTab) = state.update { s ->
        if (tab !in s.tabs) s else if (s.showing(tab)) s.copy(collapsed = true) else s.copy(selected = tab, collapsed = false)
    }

    fun close(tab: PanelTab) = state.update { s ->
        val i = s.tabs.indexOf(tab)
        if (i < 0) return@update s
        val tabs = s.tabs - tab
        s.copy(tabs = tabs, selected = if (s.selected == tab) tabs.getOrNull(i) ?: tabs.lastOrNull() else s.selected)
    }

    fun collapse(collapsed: Boolean) = state.update { it.copy(collapsed = collapsed) }

    /** Follows a drag of the panel's edge to [width]: narrower than [CollapsePanelWidth] collapses it. */
    fun resize(width: Float, max: Float) = state.update { s ->
        if (width < CollapsePanelWidth) s.copy(collapsed = true)
        else s.copy(width = width.coerceIn(MinPanelWidth, max.coerceAtLeast(MinPanelWidth)), collapsed = false)
    }
}
