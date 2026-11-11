package kinuvo.server.artifacts

import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.server.plugin.CoreTool
import kinuvo.harness.Agent
import kinuvo.protocol.ToolNames
import kinuvo.server.plugin.requireString
import kinuvo.server.plugin.string
import kinuvo.protocol.ArtifactInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Creates and edits Artifacts; [readWorkspace] reads a file from the Agent's Workspace. */
class ArtifactTool(
    private val agent: Agent,
    private val sessionId: String?,
    private val artifacts: Artifacts,
    private val readWorkspace: suspend (String) -> String,
) : CoreTool {
    override val descriptor = toolSpec(
        name = ToolNames.ARTIFACT,
        description = "Make something for the user to open from the chat and keep: a self-contained HTML page (reports, " +
            "charts, small interactive tools; inline CSS/JS or load them from https CDNs, no fetch/XHR), SVG, Markdown, CSV, " +
            "JSON or text. create returns its id; update replaces its content (only the latest is kept). Artifacts are " +
            "shared by all agents; list and read find earlier ones.",
        requiredParameters = listOf(ToolParameter("action", "create | update | list | read", ParameterType.Enum(arrayOf("create", "update", "list", "read")))),
        optionalParameters = listOf(
            ToolParameter("id", "The artifact, for update and read", ParameterType.String),
            ToolParameter("title", "Name shown to the user; required for create", ParameterType.String),
            ToolParameter("type", Artifacts.TYPES.joinToString(" | ") + "; for create, unless from_path has an extension", ParameterType.String),
            ToolParameter("content", "The full content", ParameterType.String),
            ToolParameter("from_path", "Copy the content from this workspace file instead", ParameterType.String),
            ToolParameter("old", "For update: exact text to replace, instead of content", ParameterType.String),
            ToolParameter("new", "Replacement for old", ParameterType.String),
        ),
    )

    // Models often send every optional field, empty when unused.
    private fun JsonObject.given(key: String) = string(key)?.takeIf { it.isNotEmpty() }

    override suspend fun execute(args: JsonObject): String = when (args.requireString("action")) {
        "create" -> {
            val path = args.given("from_path")
            val type = args.given("type") ?: path?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() } ?: error("Give a type")
            saved(artifacts.create(agent.id, sessionId, args.requireString("title"), type, content(args)?.takeIf { it.isNotBlank() } ?: error("Give content or from_path")))
        }
        "update" -> {
            val content = content(args)
            val old = args.given("old")
            saved(artifacts.update(args.requireString("id"), agent.id, sessionId, args.given("title")) { current ->
                when {
                    content != null -> content
                    old != null -> {
                        require(current.split(old).size == 2) { "'old' must appear exactly once" }
                        current.replace(old, args.string("new").orEmpty())
                    }
                    else -> current
                }
            })
        }
        "list" -> artifacts.list().joinToString("\n") { "${it.id} · ${it.title} · ${it.type} · by ${it.agentId}" }.ifEmpty { "No artifacts yet." }
        "read" -> artifacts.get(args.requireString("id")).content.let { if (it.length > READ_CHARS) it.take(READ_CHARS) + "\n…[${it.length - READ_CHARS} more chars]" else it }
        else -> error("Unknown action")
    }

    private suspend fun content(args: JsonObject): String? = args.given("content") ?: args.given("from_path")?.let { readWorkspace(it) }

    /** JSON, so the chat can link to the Artifact. */
    private fun saved(info: ArtifactInfo) = JsonObject(mapOf(
        "id" to JsonPrimitive(info.id),
        "title" to JsonPrimitive(info.title),
        "type" to JsonPrimitive(info.type),
        "note" to JsonPrimitive("The user can open it from the chat."),
    )).toString()

    private companion object {
        const val READ_CHARS = 100_000
    }
}
