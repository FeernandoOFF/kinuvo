package kinuvo.app.navigation

import kinuvo.app.connection.*
import kinuvo.app.agent.*

import kinuvo.protocol.ServerFeature
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A destination in the app's back stack; saved across configuration changes and process death. */
@Serializable
sealed interface Route {
    @Serializable
    data object Connect : Route

    /**
     * One Session of one Agent. A null [agentId] opens the first Agent's latest Session; a null [sessionId] is a new Session.
     * [slot] keeps the screen (and its ViewModel) when the route is filled in place, e.g. once a new Session is created.
     * [compose] marks a new Session the user asked for: it shows without a transition, ready to type in.
     */
    @Serializable
    data class Chat(val agentId: String? = null, val sessionId: String? = null, val slot: String = newSlot(), val compose: Boolean = false) : Route

    /**
     * One Agent's settings: [section] null lists the sections, [item] opens one of the section's items. With [editing],
     * the item's form is open, or a form for a new item when [item] is null.
     */
    @Serializable
    data class Settings(val agentId: String, val section: SettingsSection? = null, val item: String? = null, val editing: Boolean = false) : Route

    /** The onboarding's Identity and Soul steps, for a new Agent. */
    @Serializable
    data object NewAgent : Route

    /** The Board of the user's Tasks. */
    @Serializable
    data object Board : Route

    /** Knowledge: search and recent changes, with the [note] (a ref) open when set. */
    @Serializable
    data class Knowledge(val note: String? = null) : Route

    /** The Artifacts every Agent made, with the [artifact] (an ID) open when set. */
    @Serializable
    data class Artifacts(val artifact: String? = null) : Route

    /** Live view of the Computers Agents can control, with [computer] (an ID) shown when set. */
    @Serializable
    data class Computers(val computer: String? = null) : Route

    companion object {
        fun chat(view: LastView?) = Chat(view?.agentId, view?.sessionId)
    }
}

@Serializable
enum class SettingsSection(val title: String, val server: Boolean = false) {
    /** The Agent's own page: who it is, opening its other sections. */
    Overview("Overview"),
    /** Name, Identity, Soul and model; also where a new Agent is created. */
    Agent("Personality"), Workspace("Workspace"), Tools("Tools"), Routines("Routines"), Skills("Skills"), Mcp("MCP servers"), DeviceTools("This Device's tools"),
    /** Server-wide, the same whichever Agent is picked. */
    Connection("Connection", server = true), Providers("Model providers", server = true), Tasks("Tasks", server = true), Knowledge("Knowledge", server = true), Secrets("Secrets", server = true),
    Search("Web search", server = true), Notifications("Notifications", server = true);

    /** One Agent's, opened from its [Overview] rather than listed in Settings. */
    val agent get() = !server && this != DeviceTools && this != Overview

    /** Listed under Device or Server; the Agents list opens the [Overview]. */
    val listed get() = !agent && this != Overview

    /** The Server feature this section manages, if any. */
    val feature get() = when (this) {
        Routines -> ServerFeature.Routines
        Skills -> ServerFeature.Skills
        Mcp -> ServerFeature.Mcp
        DeviceTools -> ServerFeature.DeviceTools
        Tasks -> ServerFeature.Tasks
        Knowledge -> ServerFeature.Knowledge
        else -> null
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun newSlot() = Uuid.random().toString()
