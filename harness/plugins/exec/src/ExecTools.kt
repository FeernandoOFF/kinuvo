package kinuvo.harness.exec

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.ShellCommand
import kinuvo.protocol.Risk
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.requireString
import kinuvo.harness.core.tools.string
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

class ShellTool(private val backend: ExecBackend) : Tool {
    override val kind = kinuvo.harness.core.tools.ToolKind.Shell
    override fun assess(args: JsonObject) = args.string("command")?.let { ShellCommand.parse(it).assess(backend.roots) } ?: super.assess(args)

    override val descriptor = ToolDescriptor(
        name = ToolNames.SHELL,
        description = "Run a bash command (fresh process each call; cd/export don't persist). Runs on ${backend.label}. " +
            "Use cwd instead of cd. Output is truncated to the start and end when long.",
        requiredParameters = listOf(ToolParameterDescriptor("command", "The bash command", ToolParameterType.String)),
        optionalParameters = listOf(
            ToolParameterDescriptor("cwd", "Working directory inside the workspace", ToolParameterType.String),
            ToolParameterDescriptor("timeout", "Seconds before the command is killed (default 120, max 600)", ToolParameterType.Integer),
            ToolParameterDescriptor(
                "background",
                "Start a long-running process (server, emulator) and return immediately; check it with the process output tool",
                ToolParameterType.Boolean,
            ),
        ),
    )

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

class ReadFileTool(private val backend: ExecBackend) : Tool {
    override val risk = Risk.Low
    override val descriptor = ToolDescriptor(
        name = "read_file",
        description = "Read a text file from the workspace. offset/limit select lines (1-based).",
        requiredParameters = listOf(ToolParameterDescriptor("path", "File path (relative to the first workspace folder or absolute)", ToolParameterType.String)),
        optionalParameters = listOf(
            ToolParameterDescriptor("offset", "First line to return", ToolParameterType.Integer),
            ToolParameterDescriptor("limit", "Number of lines (default 2000)", ToolParameterType.Integer),
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

class WriteFileTool(private val backend: ExecBackend) : Tool {
    override val descriptor = ToolDescriptor(
        name = "write_file",
        description = "Create or overwrite a file in the workspace with the given content.",
        requiredParameters = listOf(
            ToolParameterDescriptor("path", "File path", ToolParameterType.String),
            ToolParameterDescriptor("content", "Full file content", ToolParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val content = args["content"]?.jsonPrimitive?.content ?: error("Missing 'content'")
        backend.write(args.requireString("path"), content)
        return "Wrote ${content.length} chars to ${backend.resolve(args.requireString("path"))}"
    }
}

class EditFileTool(private val backend: ExecBackend) : Tool {
    override val descriptor = ToolDescriptor(
        name = "edit_file",
        description = "Replace one exact, unique occurrence of old with new in a workspace file.",
        requiredParameters = listOf(
            ToolParameterDescriptor("path", "File path", ToolParameterType.String),
            ToolParameterDescriptor("old", "Exact text to replace (must occur exactly once)", ToolParameterType.String),
            ToolParameterDescriptor("new", "Replacement text", ToolParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val path = args.requireString("path")
        val old = args["old"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: error("Missing 'old'")
        val new = args["new"]?.jsonPrimitive?.content ?: error("Missing 'new'")
        val text = backend.read(path)
        val count = generateSequence(text.indexOf(old)) { i -> text.indexOf(old, i + 1).takeIf { it >= 0 } }
            .takeWhile { it >= 0 }.count()
        require(count == 1) { if (count == 0) "old text not found in $path" else "old text occurs $count times in $path; include more context" }
        backend.write(path, text.replaceFirst(old, new))
        return "Edited ${backend.resolve(path)}"
    }
}

class ListFilesTool(private val backend: ExecBackend) : Tool {
    override val risk = Risk.Low
    override val descriptor = ToolDescriptor(
        name = "list_files",
        description = "List files under a workspace folder (skips .git, build, node_modules), optionally filtered by a glob like **/*.kt.",
        optionalParameters = listOf(
            ToolParameterDescriptor("path", "Folder (default: first workspace folder)", ToolParameterType.String),
            ToolParameterDescriptor("glob", "Glob on paths relative to the folder", ToolParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String =
        backend.list(args.string("path"), args.string("glob")).joinToString("\n").ifEmpty { "(no files)" }
}

class ProcessOutputTool(private val backend: ExecBackend) : Tool {
    override val risk = Risk.Low
    override val descriptor = ToolDescriptor(
        name = "process_output",
        description = "Check a background process started with shell(background=true): running state, exit code and the end of its output.",
        requiredParameters = listOf(ToolParameterDescriptor("id", "Background process id, e.g. bg1", ToolParameterType.String)),
        optionalParameters = listOf(ToolParameterDescriptor("tail_chars", "How much of the output end to return (default 8000)", ToolParameterType.Integer)),
    )

    override suspend fun execute(args: JsonObject): String {
        val p = backend.backgroundProcess(args.requireString("id"))
        val s = backend.status(p, (args.int("tail_chars") ?: 8000).coerceIn(100, 100_000))
        val state = if (s.running) "running" else "stopped" + (s.exit?.let { " (exit $it)" } ?: "")
        return "[${p.id} $state] ${p.command}\n${s.output}"
    }
}

class ProcessKillTool(private val backend: ExecBackend) : Tool {
    override val risk = Risk.Low
    override val descriptor = ToolDescriptor(
        name = "process_kill",
        description = "Stop a background process started with shell(background=true).",
        requiredParameters = listOf(ToolParameterDescriptor("id", "Background process id", ToolParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String {
        val p = backend.backgroundProcess(args.requireString("id"))
        backend.kill(p)
        return "Stopped ${p.id}."
    }
}

