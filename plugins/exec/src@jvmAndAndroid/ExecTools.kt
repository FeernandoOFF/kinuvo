package kinuvo.plugins.exec

import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.server.plugin.CoreTool
import kinuvo.server.plugin.ShellCommand
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.ToolRun
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.plugin.requireString
import kinuvo.server.plugin.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

fun execTools(backend: ExecBackend): List<Tool> = listOf(
    ShellTool(backend),
    ReadFileTool(backend),
    WriteFileTool(backend),
    EditFileTool(backend),
    ListFilesTool(backend),
    ProcessOutputTool(backend),
    ProcessKillTool(backend),
)

private fun JsonObject.int(key: String) = get(key)?.jsonPrimitive?.intOrNull

class ShellTool(private val backend: ExecBackend) : CoreTool {
    override fun assess(args: JsonObject) = args.string("command")?.let { ShellCommand.parse(it).assess(backend.roots) } ?: super.assess(args)

    override val descriptor = toolSpec(
        name = ToolNames.SHELL,
        description = "Run a bash command (fresh process each call; cd/export don't persist). Runs on ${backend.label}. " +
            "Use cwd instead of cd. Output is truncated to the start and end when long.",
        requiredParameters = listOf(ToolParameter("command", "The bash command", ParameterType.String)),
        optionalParameters = listOf(
            ToolParameter("watch", "Stream output while waiting for completion", ParameterType.Boolean),
            ToolParameter("cwd", "Working directory inside the workspace", ParameterType.String),
            ToolParameter("timeout", "Seconds before the command is killed (default 120, max 600)", ParameterType.Integer),
            ToolParameter(
                "background",
                "Start a long-running process (server, emulator) and return immediately; completion is reported automatically",
                ParameterType.Boolean,
            ),
        ),
    )

    override suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String {
        if (args["watch"]?.jsonPrimitive?.booleanOrNull == true && args["background"]?.jsonPrimitive?.booleanOrNull != true) {
            val p = backend.startBackground(args.requireString("command"), args.string("cwd"))
            var finished = false
            val timeout = (args.int("timeout") ?: 120).coerceIn(1, 600).seconds
            try {
                return withTimeoutOrNull(timeout) {
                    var previous = ""
                    while (true) {
                        val status = backend.status(p, 8000)
                        if (status.output != previous) { run.progress(status.output); previous = status.output }
                        if (!status.running) { finished = true; break }
                        delay(1000)
                    }
                    val status = backend.status(p, 8000)
                    "[exit ${status.exit ?: "unknown"}]\n${status.output}"
                } ?: "[timed out after $timeout]\n${backend.status(p, 8000).output}"
            } finally { if (!finished) withContext(NonCancellable) { backend.kill(p) } }
        }
        if (args["background"]?.jsonPrimitive?.booleanOrNull != true) return execute(args)
        val p = backend.startBackground(args.requireString("command"), args.string("cwd"))
        return run.background("Started background process ${p.id} (pid ${p.pid}), log ${p.log}.") {
            var status = backend.status(p, 8000)
            while (status.running) {
                delay(1000)
                status = backend.status(p, 8000)
            }
            "[${p.id} ${status.exit?.let { if (it == "killed") "killed" else "exit $it" } ?: "stopped"}] ${p.command}\n${status.output}"
        }
    }

    override suspend fun execute(args: JsonObject): String {
        if (args["background"]?.jsonPrimitive?.booleanOrNull == true) {
            val p = backend.startBackground(args.requireString("command"), args.string("cwd"))
            return "Started background process ${p.id} (pid ${p.pid}), log ${p.log}. Use the process output or process kill tool with id ${p.id}."
        }
        val timeout = (args.int("timeout") ?: 120).coerceIn(1, 600).seconds
        val r = backend.run(args.requireString("command"), args.string("cwd"), timeout)
        val status = if (r.timedOut) "timed out after $timeout" else "exit code ${r.exitCode}"
        return "[$status]\n${r.output}"
    }
}

class ReadFileTool(private val backend: ExecBackend) : CoreTool {
    override val descriptor = toolSpec(
        name = "read_file",
        description = "Read a text file from the workspace. offset/limit select lines (1-based).",
        requiredParameters = listOf(ToolParameter("path", "File path (relative to the first workspace folder or absolute)", ParameterType.String)),
        optionalParameters = listOf(
            ToolParameter("offset", "First line to return", ParameterType.Integer),
            ToolParameter("limit", "Number of lines (default 2000)", ParameterType.Integer),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val lines = backend.read(args.requireString("path")).lines()
        val from = ((args.int("offset") ?: 1) - 1).coerceIn(0, lines.size)
        val slice = lines.drop(from).take(args.int("limit") ?: 2000)
        val more = if (from + slice.size < lines.size) "\n…[${lines.size - from - slice.size} more lines]" else ""
        return slice.withIndex().joinToString("\n") { (i, l) -> "${from + i + 1}\t$l" } + more
    }
}

class WriteFileTool(private val backend: ExecBackend) : CoreTool {
    override val descriptor = toolSpec(
        name = "write_file",
        description = "Create or overwrite a file in the workspace with the given content.",
        requiredParameters = listOf(
            ToolParameter("path", "File path", ParameterType.String),
            ToolParameter("content", "Full file content", ParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val content = args["content"]?.jsonPrimitive?.content ?: error("Missing 'content'")
        backend.write(args.requireString("path"), content)
        return "Wrote ${content.length} chars to ${backend.resolve(args.requireString("path"))}"
    }
}

class EditFileTool(private val backend: ExecBackend) : CoreTool {
    override val descriptor = toolSpec(
        name = "edit_file",
        description = "Replace one exact, unique occurrence of old with new in a workspace file.",
        requiredParameters = listOf(
            ToolParameter("path", "File path", ParameterType.String),
            ToolParameter("old", "Exact text to replace (must occur exactly once)", ParameterType.String),
            ToolParameter("new", "Replacement text", ParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val path = args.requireString("path")
        val old = args["old"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: error("Missing 'old'")
        val new = args["new"]?.jsonPrimitive?.content ?: error("Missing 'new'")
        val text = backend.read(path, whole = true)
        val count = generateSequence(text.indexOf(old)) { i -> text.indexOf(old, i + 1).takeIf { it >= 0 } }
            .takeWhile { it >= 0 }.count()
        require(count == 1) { if (count == 0) "old text not found in $path" else "old text occurs $count times in $path; include more context" }
        backend.write(path, text.replaceFirst(old, new))
        return "Edited ${backend.resolve(path)}"
    }
}

class ListFilesTool(private val backend: ExecBackend) : CoreTool {
    override val descriptor = toolSpec(
        name = "list_files",
        description = "List files under a workspace folder (skips .git, build, node_modules), optionally filtered by a glob like **/*.kt.",
        optionalParameters = listOf(
            ToolParameter("path", "Folder (default: first workspace folder)", ParameterType.String),
            ToolParameter("glob", "Glob on paths relative to the folder", ParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String =
        backend.list(args.string("path"), args.string("glob")).joinToString("\n").ifEmpty { "(no files)" }
}

class ProcessOutputTool(private val backend: ExecBackend) : CoreTool {
    override val descriptor = toolSpec(
        name = "process_output",
        description = "Check a background process started with shell(background=true): running state, exit code and the end of its output.",
        requiredParameters = listOf(ToolParameter("id", "Background process id, e.g. bg1", ParameterType.String)),
        optionalParameters = listOf(ToolParameter("tail_chars", "How much of the output end to return (default 8000)", ParameterType.Integer)),
    )

    override suspend fun execute(args: JsonObject): String {
        val p = backend.backgroundProcess(args.requireString("id"))
        val s = backend.status(p, (args.int("tail_chars") ?: 8000).coerceIn(100, 100_000))
        val state = if (s.running) "running" else "stopped" + (s.exit?.let { " (exit $it)" } ?: "")
        return "[${p.id} $state] ${p.command}\n${s.output}"
    }
}

class ProcessKillTool(private val backend: ExecBackend) : CoreTool {
    override val descriptor = toolSpec(
        name = "process_kill",
        description = "Stop a background process started with shell(background=true).",
        requiredParameters = listOf(ToolParameter("id", "Background process id", ParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String {
        val p = backend.backgroundProcess(args.requireString("id"))
        backend.kill(p)
        return "Stopped ${p.id}."
    }
}

