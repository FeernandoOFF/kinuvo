package kinuvo.server.artifacts

import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.server.plugin.CoreTool
import kinuvo.harness.Agent
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.plugin.requireString
import kinuvo.server.plugin.string
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.artifact.ArtifactBundle
import kinuvo.protocol.artifact.ArtifactManifest
import kinuvo.protocol.artifact.ArtifactInfo
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.artifact.Artboard
import kinuvo.protocol.approval.Risk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The Agent's Workspace, to copy files and folders into Artifacts; paths are the Agent's own. */
interface WorkspaceFiles {
    suspend fun text(path: String): String
    suspend fun bytes(path: String, maxBytes: Int): ByteArray

    /** The files under [path] relative to it; empty when it is a file or has none. */
    suspend fun list(path: String, limit: Int): List<String>
}

/** Creates and edits Artifacts, from content or from files in the Agent's [workspace]. */
class ArtifactTool(
    private val agent: Agent,
    private val sessionId: SessionId?,
    private val artifacts: Artifacts,
    private val workspace: WorkspaceFiles,
) : CoreTool {
    override val descriptor = toolSpec(
        name = ToolNames.ARTIFACT,
        description = "Make something for the user to open from the chat and keep: a self-contained HTML page (reports, " +
            "charts, small interactive tools; inline CSS/JS or load them from https CDNs, no fetch/XHR), SVG, Markdown, CSV, " +
            "JSON or text. A design holds artboards, JSON {\"artboards\":[{\"id\",\"name\",\"width\",\"height\",\"html\"}]}, " +
            "each a self-contained HTML page at a fixed size, shown left to right; prefer artboard_set/artboard_remove for " +
            "single-artboard changes. HTML follows the app's light/dark theme: define colours as CSS variables on :root, override them under " +
            ":root[data-theme=dark] (the app sets data-theme) and under @media (prefers-color-scheme: dark) for :root:not([data-theme=light]), " +
            "and give body an explicit background; slide decks (e.g. reveal.js) keep speaker notes in <aside class=notes>, which the user sees from Open in browser. " +
            "create returns its id; update replaces its content (only the latest is kept). " +
            "An HTML artifact can be a folder: file_write adds files at relative paths (index.html is the entry page; app.js, img/a.png, module.wasm, data.json, fonts, .sqlite) " +
            "and pages load them with relative URLs, never /absolute paths, and may fetch() only their own files. An optional manifest.json is " +
            "{\"entry\":\"index.html\",\"title\":\"...\",\"capabilities\":{\"network\":[],\"popups\":false,\"storage\":false,\"tools\":[]}}. " +
            "Prefer from_path (a workspace file, or a whole folder such as a build's dist) and file_edit over writing large files in content: " +
            "regenerating them takes minutes. Limits: ${ArtifactBundle.MAX_FILE_BYTES / 1_000_000} MB a file, ${ArtifactBundle.MAX_BUNDLE_BYTES / 1_000_000} MB and ${ArtifactBundle.MAX_FILES} files in all. " +
            "Artifacts are shared by all agents; list and read find earlier ones.",
        requiredParameters = listOf(ToolParameter("action", ACTIONS.joinToString(" | "), ParameterType.Enum(ACTIONS.toTypedArray()))),
        optionalParameters = listOf(
            ToolParameter("id", "The artifact, for everything but create and list; update on a folder changes its entry page", ParameterType.String),
            ToolParameter("title", "Name shown to the user; required for create", ParameterType.String),
            ToolParameter("type", Artifacts.TYPES.joinToString(" | ") + "; for create, unless from_path has an extension", ParameterType.String),
            ToolParameter("content", "The full content", ParameterType.String),
            ToolParameter("from_path", "Copy the content from this workspace file instead; a folder makes create an HTML folder, and file_write add all its files under path", ParameterType.String),
            ToolParameter("path", "For file_write, file_edit, file_delete and file_read: the file inside the folder, e.g. app.js or img/logo.png; the folder to copy into for file_write with a from_path folder", ParameterType.String),
            ToolParameter("old", "For update and file_edit: exact text to replace, instead of content", ParameterType.String),
            ToolParameter("new", "Replacement for old", ParameterType.String),
            ToolParameter("artboard", "For artboard_set and artboard_remove: the artboard id; id is the artifact", ParameterType.String),
            ToolParameter("name", "For artboard_set: its label (default the artboard id)", ParameterType.String),
            ToolParameter("width", "For artboard_set: pixels (default 390)", ParameterType.Integer),
            ToolParameter("height", "For artboard_set: pixels (default 844)", ParameterType.Integer),
            ToolParameter("html", "For artboard_set: the page; required for a new artboard", ParameterType.String),
        ),
    )

    // Models often send every optional field, empty when unused.
    private fun JsonObject.given(key: String) = string(key)?.takeIf { it.isNotEmpty() }

    override suspend fun execute(args: JsonObject): String = when (args.requireString("action")) {
        "create" -> {
            val path = args.given("from_path")
            val folder = path?.let { workspace.list(it, LISTED) }.orEmpty()
            if (path != null && folder.isNotEmpty()) {
                val (files, skipped) = copied(path, folder, "")
                saved(artifacts.createBundle(agent.id, sessionId, args.requireString("title"), files), skipped)
            } else {
                val type = args.given("type") ?: path?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() } ?: error("Give a type")
                saved(artifacts.create(agent.id, sessionId, args.requireString("title"), type, content(args)?.takeIf { it.isNotBlank() } ?: error("Give content or from_path")))
            }
        }
        "file_write" -> {
            val from = args.given("from_path")
            val path = args.given("path")
            val folder = from?.let { workspace.list(it, LISTED) }.orEmpty()
            val (files, skipped) = when {
                from != null && folder.isNotEmpty() -> copied(from, folder, path?.trim('/')?.plus("/").orEmpty())
                from != null -> mapOf((path ?: from.substringAfterLast('/')) to workspace.bytes(from, ArtifactBundle.MAX_FILE_BYTES)) to emptyList()
                else -> mapOf((path ?: error("Give a path")) to (args.given("content") ?: error("Give content or from_path")).encodeToByteArray()) to emptyList()
            }
            saved(artifacts.writeFiles(args.requireString("id"), agent.id, sessionId, files), skipped)
        }
        "file_edit" -> {
            val id = args.requireString("id")
            val path = args.given("path") ?: error("Give a path")
            val old = args.given("old") ?: error("Give old")
            val current = artifacts.fileBytes(id, path).decodeToString()
            require(current.split(old).size == 2) { "'old' must appear exactly once" }
            saved(artifacts.writeFiles(id, agent.id, sessionId, mapOf(path to current.replace(old, args.string("new").orEmpty()).encodeToByteArray())))
        }
        "file_delete" -> saved(artifacts.deleteFile(args.requireString("id"), agent.id, sessionId, args.given("path") ?: error("Give a path")))
        "files" -> artifacts.files(args.requireString("id")).let { listing ->
            "entry: ${listing.entry}\n" + listing.files.joinToString("\n") { "${it.path} · ${it.size} bytes · ${it.mime}" } +
                listing.manifest?.let { "\nmanifest: ${ProtocolJson.encodeToString(ArtifactManifest.serializer(), it)}" }.orEmpty()
        }
        "file_read" -> artifacts.fileBytes(args.requireString("id"), args.given("path") ?: error("Give a path")).let { bytes ->
            val mime = ArtifactBundle.mimeOf(args.given("path").orEmpty()).orEmpty()
            if (!ArtifactBundle.isText(mime)) "${bytes.size} bytes of $mime, not text"
            else bytes.decodeToString().let { if (it.length > READ_CHARS) it.take(READ_CHARS) + "\n…[${it.length - READ_CHARS} more chars]" else it }
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
        "artboard_set" -> {
            val board = args.requireString("artboard")
            val html = args.given("html")
            val width = args.given("width")?.toIntOrNull()
            val height = args.given("height")?.toIntOrNull()
            val name = args.given("name")
            saved(artifacts.update(args.requireString("id"), agent.id, sessionId) { current ->
                val design = Artifacts.parseDesign(current)
                val old = design.artboards.firstOrNull { it.id == board }
                require(old != null || html != null) { "A new artboard needs html" }
                val next = Artboard(board, name ?: old?.name ?: board, width ?: old?.width ?: 390, height ?: old?.height ?: 844, old?.x, old?.y, html ?: old?.html.orEmpty())
                Artifacts.encodeDesign(design.copy(artboards = if (old == null) design.artboards + next else design.artboards.map { if (it.id == board) next else it }))
            })
        }
        "artboard_remove" -> {
            val board = args.requireString("artboard")
            saved(artifacts.update(args.requireString("id"), agent.id, sessionId) { current ->
                val design = Artifacts.parseDesign(current)
                require(design.artboards.any { it.id == board }) { "No artboard '$board'" }
                Artifacts.encodeDesign(design.copy(artboards = design.artboards.filter { it.id != board }))
            })
        }
        "list" -> artifacts.list().joinToString("\n") { "${it.id} · ${it.title} · ${it.type} · by ${it.agentId}" }.ifEmpty { "No artifacts yet." }
        "read" -> artifacts.get(args.requireString("id")).content.let { if (it.length > READ_CHARS) it.take(READ_CHARS) + "\n…[${it.length - READ_CHARS} more chars]" else it }
        else -> error("Unknown action")
    }

    private suspend fun content(args: JsonObject): String? = args.given("content") ?: args.given("from_path")?.let { workspace.text(it) }

    /**
     * The files of the Workspace folder [root], each at [prefix] plus its path in it, and the paths left out: hidden files, and types
     * Artifacts don't hold.
     */
    private suspend fun copied(root: String, relative: List<String>, prefix: String): Pair<Map<String, ByteArray>, List<String>> {
        val (kept, left) = relative.partition { path -> path.split('/').none { it.startsWith(".") } && ArtifactBundle.mimeOf(path) != null }
        require(kept.size <= ArtifactBundle.MAX_FILES) { "$root has ${kept.size} files; an artifact holds at most ${ArtifactBundle.MAX_FILES}" }
        var total = 0L
        val files = kept.associate { path ->
            val bytes = workspace.bytes("${root.trimEnd('/')}/$path", ArtifactBundle.MAX_FILE_BYTES)
            total += bytes.size
            require(total <= ArtifactBundle.MAX_BUNDLE_BYTES) { "$root is over ${ArtifactBundle.MAX_BUNDLE_BYTES / 1_000_000} MB" }
            "$prefix$path" to bytes
        }
        require(files.isNotEmpty()) { "$root has no files an artifact can hold" }
        return files to left.filter { path -> path.split('/').none { it.startsWith(".") } }
    }

    /** JSON, so the chat can link to the Artifact; [skipped] lists copied files that were left out. */
    private fun saved(info: ArtifactInfo, skipped: List<String> = emptyList()) = JsonObject(buildMap {
        put("id", JsonPrimitive(info.id))
        put("title", JsonPrimitive(info.title))
        put("type", JsonPrimitive(info.type))
        if (info.bundle) put("folder", JsonPrimitive(true))
        if (skipped.isNotEmpty()) put("skipped", JsonArray(skipped.take(SKIPPED_SHOWN).map(::JsonPrimitive)))
        put("note", JsonPrimitive("The user can open it from the chat."))
    }).toString()

    private companion object {
        const val READ_CHARS = 100_000
        const val LISTED = 2_000
        const val SKIPPED_SHOWN = 20
        val ACTIONS = listOf("create", "update", "list", "read", "artboard_set", "artboard_remove", "files", "file_write", "file_edit", "file_delete", "file_read")
    }
}
