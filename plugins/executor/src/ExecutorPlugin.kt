package kinuvo.plugins.executor

import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.server.plugin.CoreTool
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.PluginTools
import kinuvo.server.plugin.RunContext
import kinuvo.protocol.ToolNames
import kinuvo.server.plugin.ToolRun
import kinuvo.server.plugin.requireString
import kinuvo.protocol.Risk
import kotlinx.serialization.json.JsonObject
import kinuvo.protocol.Integration
import kinuvo.protocol.PluginDefinition

/** Contributes [RunCodeTool] to Agents with the `run_code` tool group. */
class ExecutorPlugin(private val limits: Limits = Limits()) : HarnessPlugin {
    override val definition = PluginDefinition("kinuvo.executor", "Scripts")
    override val integrations = mapOf(Integration.Tools to setOf(ToolNames.RUN_CODE))
    override suspend fun prepare(run: RunContext, activate: Boolean) =
        if (ToolNames.RUN_CODE in run.agent.config.tools) PluginTools(listOf(RunCodeTool(limits))) else PluginTools()
}

/** Runs a Script; its own Risk is Low since every tool it calls goes through its own Approval. */
class RunCodeTool(private val limits: Limits = Limits()) : CoreTool {
    override val descriptor = toolSpec(
        ToolNames.RUN_CODE,
        "Run a JavaScript Script to chain, loop over or filter many tool calls in one step. The code is the body of an async " +
            "function: call your other tools as `await tools.<name>({...args})` (results are parsed JSON or text; a failed or " +
            "denied call throws) and `return` what you need; only the return value, console output and a list of the calls come " +
            "back. No files, network or timers except through tools. Limits: ${limits.calls} tool calls, ${limits.timeout} of JavaScript, " +
            "${limits.output} characters of output.",
        requiredParameters = listOf(ToolParameter("code", "The async function body; use the exact advertised tool names as properties of tools", ParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String = error("A Script needs a run to call tools in")

    override suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String {
        val calls = mutableListOf<String>()
        val result = runScript(args.requireString("code"), run.tools.map { it.name }, limits) { name, json ->
            run.call(name, json).also { r ->
                calls += "- ${r.name}(${capped(json, 120)}) → " +
                    if (r.isError) "error: ${capped(r.output, 200)}" else "ok, ${r.output.length} characters"
            }
        }
        val summary = if (calls.isEmpty()) "" else "\n\nTool calls (${calls.size}):\n" + calls.joinToString("\n")
        val body = buildString {
            append(result.error?.let { "Script failed: $it" } ?: result.value ?: "(no return value)")
            if (result.logs.isNotBlank()) append("\n\nConsole:\n").append(result.logs.trimEnd())
        }
        val max = (limits.output - if (result.error != null) "Error: ".length else 0).coerceAtLeast(0)
        val text = capped(body, (max - summary.length).coerceAtLeast(0)) + capped(summary, max)
        check(result.error == null) { text }
        return text
    }
}
