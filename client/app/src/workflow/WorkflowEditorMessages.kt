package kinuvo.app.workflow

import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.model.ModelsCatalog
import kinuvo.protocol.workflow.StepId
import kinuvo.protocol.workflow.Workflow
import kinuvo.protocol.workflow.WorkflowRun
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class EditorAgent(val id: AgentId, val name: String)
@Serializable data class EditorModel(val provider: String, val model: String, val name: String, val providerName: String)

/** What the editor page offers in its pickers; [tools] are the names the Workflow's Agent can call, empty when unknown, and [inputs] the Workflow's input names. */
@Serializable
data class EditorCatalog(val agents: List<EditorAgent> = emptyList(), val models: List<EditorModel> = emptyList(), val tools: List<String> = emptyList(),
    val inputs: List<String> = emptyList()) {
    companion object {
        fun of(agents: List<AgentInfo>, models: ModelsCatalog, tools: List<String>, inputs: List<String> = emptyList()) = EditorCatalog(
            agents.map { EditorAgent(it.id, it.name) },
            models.providers.flatMap { p -> p.models.filter { it.state.actionable }.map { EditorModel(p.id, it.id, it.name, p.name) } },
            tools, inputs,
        )
    }
}

/** CSS colors for the page, taken from the app theme. */
@Serializable
data class EditorTheme(val dark: Boolean, val background: String, val panel: String, val subtle: String, val text: String, val muted: String,
    val border: String, val accent: String, val onAccent: String, val success: String, val warning: String, val danger: String)

/** Kotlin to page. The page holds [Workflow] as a view only; [Load] replaces its model. */
@Serializable
sealed interface EditorCommand {
    @Serializable @SerialName("load") data class Load(val workflow: Workflow, val catalog: EditorCatalog, val theme: EditorTheme, val run: WorkflowRun? = null) : EditorCommand
    @Serializable @SerialName("catalog") data class CatalogChanged(val catalog: EditorCatalog) : EditorCommand
    @Serializable @SerialName("run") data class RunChanged(val run: WorkflowRun?) : EditorCommand
    @Serializable @SerialName("theme") data class ThemeChanged(val theme: EditorTheme) : EditorCommand
}

/** Page to Kotlin. [Changed] carries the whole edited Workflow. */
@Serializable
sealed interface EditorEvent {
    @Serializable @SerialName("ready") data object Ready : EditorEvent
    @Serializable @SerialName("changed") data class Changed(val workflow: Workflow) : EditorEvent
    @Serializable @SerialName("open_session") data class OpenSession(val step: StepId) : EditorEvent
}

object WorkflowEditorMessages {
    fun encode(command: EditorCommand): String = ProtocolJson.encodeToString(EditorCommand.serializer(), command)

    /** Null for anything that is not an editor event, such as the web view's own notifications. */
    fun decode(raw: String): EditorEvent? = runCatching { ProtocolJson.decodeFromString(EditorEvent.serializer(), raw) }.getOrNull()
}
