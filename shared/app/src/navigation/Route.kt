package kinuvo.app.navigation

import kinuvo.app.connection.*
import kinuvo.app.agent.*

import kinuvo.protocol.Integration
import kinuvo.protocol.ServerCapabilities
import kinuvo.protocol.ServerFeature
import kinuvo.protocol.offers
import kinuvo.protocol.supports
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A destination in the app's back stack; saved across configuration changes and process death. */
@Serializable
sealed interface Route {
    @Serializable
    data object Connect : Route

    /** The Server's Today: what needs the user, what runs now and next, Tasks due and recent results, across Agents. */
    @Serializable
    data object Home : Route

    /** One Task from the Board with the Sessions of its runs. */
    @Serializable
    data class Task(val id: String) : Route

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

    /** The Artifacts every Agent made, with the [artifact] (an ID) open when set; [fullScreen] shows only it, without the shell. */
    @Serializable
    data class Artifacts(val artifact: String? = null, val fullScreen: Boolean = false) : Route

    /** Live view of the Computers Agents can control, with [computer] (an ID) shown when set. */
    @Serializable
    data class Computers(val computer: String? = null, val reason: String? = null) : Route

    @Serializable
    data class Browser(val agentId: String, val sessionId: String, val screen: kinuvo.protocol.ScreenRef? = null, val reason: String? = null) : Route

    companion object {
        fun chat(view: LastView?) = Chat(view?.agentId, view?.sessionId)
    }
}

/** Screens the shell lists under Home rather than under an Agent. */
val Route.home get() = this == Route.Home || this is Route.Task || this == Route.Board || this is Route.Knowledge || this is Route.Artifacts || this is Route.Computers || this is Route.Settings && section?.home == true

@Serializable
enum class SettingsSection(val title: String, val server: Boolean = false) {
    /** The Agent's own page: who it is, opening its other sections. */
    Overview("Overview"),
    /** Name, Identity, Soul and model; also where a new Agent is created. */
    Agent("Personality"), Workspace("Workspace"), Tools("Tools"), Routines("Routines"), Skills("Skills"), Mcp("MCP servers"), DeviceTools("This Device's tools"),
    /** Server-wide, the same whichever Agent is picked. */
    Connection("Servers & devices", server = true), Providers("Model providers", server = true),
    /** The ways this Server runs Agents besides Kinuvo itself: ACP commands and linked Kinuvo Servers. */
    Harnesses("Harnesses", server = true),
    /** Every Agent's tools and MCP servers, who can use them, and the rules for all Agents. */
    Hub("Tools & MCP", server = true), Tasks("Tasks", server = true), Knowledge("Knowledge", server = true), Secrets("Secrets", server = true),
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

    /** What this section sets up, which the Server must offer. */
    val integration get() = when (this) {
        Workspace -> Integration.Exec
        Harnesses -> Integration.Harness
        Mcp -> Integration.Mcp
        Secrets -> Integration.Secrets
        Knowledge -> Integration.Knowledge
        Tasks -> Integration.Tasks
        Search -> Integration.Search
        Notifications -> Integration.Notifications
        else -> null
    }
}

/** Whether the connected Server has the feature and options [section] needs. */
fun ServerCapabilities?.allows(section: SettingsSection) =
    (section.feature?.let(::supports) ?: true) && (section.integration?.let { offers(it) } ?: true)

@OptIn(ExperimentalUuidApi::class)
private fun newSlot() = Uuid.random().toString()

/** Configuration opened from Home, with an Agent selector. */
val SettingsSection.home get() = this in listOf(SettingsSection.Routines, SettingsSection.Hub, SettingsSection.Mcp, SettingsSection.Tools)
