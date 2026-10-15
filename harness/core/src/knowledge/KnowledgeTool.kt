package kinuvo.harness.core.knowledge

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.protocol.Risk
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.Assessment
import kinuvo.harness.core.tools.Action
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.ToolRun
import kinuvo.harness.core.tools.requireString
import kinuvo.harness.core.tools.string
import kinuvo.protocol.KnowledgeSchema
import kinuvo.protocol.Note
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads and adds to the user's Knowledge, in their system's own types and properties; every change can be undone. */
class KnowledgeTool(private val knowledge: Knowledge, private val source: ChangeSource) : Tool {
    override val risk = Risk.Low

    override val descriptor = ToolDescriptor(
        name = ToolNames.KNOWLEDGE,
        description = "The user's knowledge base (their own note system). Follow the user's conventions in your instructions. " +
            "schema lists its note types, properties and templates; search finds notes; read shows one with its body; create makes a " +
            "note, from one of its type's templates if given, appending Markdown to it (under a template heading if given); update sets properties, appends Markdown (under a heading if given) or turns text in the body into a link. " +
            "Images, PDFs and other files in a body read [[ref|name]] (image), (pdf) or (file); file shows you the one with that ref. " +
            "Link to other notes in Markdown with [[note title]]. Search before " +
            "creating so you don't make duplicates. The user sees every change and can undo it.",
        requiredParameters = listOf(
            ToolParameterDescriptor("action", "schema | search | read | file | create | update", ToolParameterType.Enum(ACTIONS)),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("reason", "Reason for a background Reflection proposal", ToolParameterType.String),
            ToolParameterDescriptor("evidence", "Source identifiers supporting a background Reflection proposal", ToolParameterType.String),
            ToolParameterDescriptor("query", "Text to search for (search)", ToolParameterType.String),
            ToolParameterDescriptor("type", "Note type key or name (search, create)", ToolParameterType.String),
            ToolParameterDescriptor("ref", "Note ref or exact title (read, update), or a file's ref (file)", ToolParameterType.String),
            ToolParameterDescriptor("title", "Note title (create, or rename on update)", ToolParameterType.String),
            ToolParameterDescriptor("set", "JSON object of property values by key or name; null clears one (create, update)", ToolParameterType.String),
            ToolParameterDescriptor("append", "Markdown to add to the body (create, update)", ToolParameterType.String),
            ToolParameterDescriptor("under", "Heading to append under, created when missing (create, update)", ToolParameterType.String),
            ToolParameterDescriptor("template", "Name of one of the type's templates to start from (create)", ToolParameterType.String),
            ToolParameterDescriptor("link_text", "Text already in the body to turn into a link (update)", ToolParameterType.String),
            ToolParameterDescriptor("link_to", "Ref or title of the note link_text should point to (update)", ToolParameterType.String),
        ),
    )

    override fun assess(args: JsonObject): Assessment {
        val writing = args.string("action") in listOf("create", "update")
        return Assessment(listOf(Action(if (writing) Risk.Medium else Risk.Low, listOf(ToolNames.KNOWLEDGE),
            if (writing) "Changes the user's Knowledge" else null)))
    }

    override suspend fun execute(args: JsonObject): String = execute(args, "", object : ToolRun {
        override val tools = emptyList<ToolDescriptor>()
        override suspend fun call(name: String, args: String) = error("No tools in this run")
    })

    // Some providers send every optional parameter, blank when unused.
    override suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String {
        val a = JsonObject(args.filterValues { (it as? JsonPrimitive)?.content?.isNotBlank() != false })
        return when (val action = a.requireString("action")) {
            "schema" -> knowledge.schema().describe()
            "search" -> knowledge.search(a.string("query").orEmpty(), a.string("type"), 15)
                .joinToString("\n") { "- ${it.title} (${it.type}), ref ${it.ref}" + it.snippet.takeIf { s -> s.isNotBlank() }?.let { s -> ": $s" }.orEmpty() }
                .ifEmpty { "No matches." }
            "read" -> knowledge.find(a.requireString("ref"))?.describe() ?: "No note '${a.string("ref")}'."
            "file" -> knowledge.file(a.requireString("ref")).let { "Attached ${run.attach(it.name, it.bytes).name} (${it.mimeType})." }
            "create" -> knowledge.create(a.requireString("type"), a.requireString("title"), set(a), a.string("append").orEmpty(), source, a.string("under"), a.string("template"))
                .let { "Created.\n${it.describe()}" }
            "update" -> {
                val body = listOfNotNull(
                    a.string("append")?.let { BodyEdit.Append(it, a.string("under")) },
                    a.string("link_text")?.let { BodyEdit.Link(it, a.requireString("link_to")) },
                )
                knowledge.update(a.requireString("ref"), a.string("title"), set(a), body, source).let { "Updated.\n${it.describe()}" }
            }
            else -> error("Unknown action '$action'")
        }
    }

    suspend fun executeReviewed(args: JsonObject, note: Note?, scope: String): String {
        val a = JsonObject(args.filterValues { (it as? JsonPrimitive)?.content?.isNotBlank() != false })
        val edit = when (a.requireString("action")) {
            "create" -> NoteEdit(null, a.requireString("type"), a.requireString("title"), set(a),
                listOfNotNull(a.string("append")?.let { BodyEdit.Append(it, a.string("under")) }), a.string("template"))
            "update" -> NoteEdit(a.requireString("ref"), title = a.string("title"), set = set(a), body = listOfNotNull(
                a.string("append")?.let { BodyEdit.Append(it, a.string("under")) },
                a.string("link_text")?.let { BodyEdit.Link(it, a.requireString("link_to")) },
            ))
            else -> error("Only Knowledge writes can be reviewed")
        }
        return knowledge.reviewed(edit, source, scope, note).describe()
    }

    private fun set(a: JsonObject): Map<String, String?> = a.string("set")?.let {
        runCatching { Json.decodeFromString(MapSerializer(String.serializer(), String.serializer().nullable), it) }
            .getOrElse { e -> throw IllegalArgumentException("'set' isn't a JSON object of strings: ${e.message}") }
    }.orEmpty()

    private companion object {
        val ACTIONS = arrayOf("schema", "search", "read", "file", "create", "update")
    }
}

/** Replaces text in a note's body; it needs the user's Approval, since it changes what they wrote. */
class KnowledgeRewriteTool(private val knowledge: Knowledge, private val source: ChangeSource) : Tool {
    override val risk = Risk.Medium

    override val descriptor = ToolDescriptor(
        name = ToolNames.KNOWLEDGE_REWRITE,
        description = "Replace text in the body of a note in the user's knowledge base, e.g. to correct a fact. old must occur " +
            "exactly once. Prefer the knowledge tool's update to add information; use this only to change existing text.",
        requiredParameters = listOf(
            ToolParameterDescriptor("ref", "Note ref or exact title", ToolParameterType.String),
            ToolParameterDescriptor("old", "The exact text to replace", ToolParameterType.String),
            ToolParameterDescriptor("new", "The replacement Markdown", ToolParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val note = knowledge.update(args.requireString("ref"), body = listOf(BodyEdit.Replace(args.requireString("old"), args.string("new").orEmpty())), source = source)
        return "Updated.\n${note.describe()}"
    }
}

internal fun KnowledgeSchema.describe() = buildString {
    append("Types:\n")
    types.forEach { t ->
        append("- ${t.name} (${t.key})")
        if (t.description.isNotBlank()) append(": ${t.description}")
        if (t.properties.isNotEmpty()) append(" [${t.properties.joinToString()}]")
        if (t.templates.isNotEmpty()) append(" templates: ${t.templates.joinToString()}")
        append('\n')
    }
    if (properties.isNotEmpty()) append("Properties:\n").also { properties.forEach { p -> append("- ${p.name} (${p.key}, ${p.format})\n") } }
    if (open) append("New types and properties may be used too.\n")
}

internal fun Note.describe() = buildString {
    append("$title ($type), ref $ref\n")
    properties.forEach { (k, v) -> append("$k: $v\n") }
    if (body.isNotBlank()) append("\n${body.trim().take(8_000)}\n")
}
