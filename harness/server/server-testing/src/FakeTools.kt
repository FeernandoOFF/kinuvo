package kinuvo.harness.server.testing

import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.McpApps
import kinuvo.server.plugin.PluginTools
import kinuvo.server.plugin.instance
import kinuvo.server.plugin.ownedBy
import kinuvo.server.plugin.RunContext
import kinuvo.server.plugin.Action
import kinuvo.server.plugin.Assessment
import kinuvo.server.plugin.Tool
import kinuvo.protocol.Integration
import kinuvo.protocol.McpAppRef
import kinuvo.protocol.McpAppResource
import kinuvo.protocol.IntegrationOptions
import kinuvo.protocol.PluginDefinition
import kinuvo.protocol.Risk
import kotlinx.serialization.json.JsonObject

/**
 * A tool a script can call. At a [risk] above the Agent's tolerance a call waits for an Approval showing [reason]; a
 * [run] that throws becomes an error result.
 */
class FakeTool(
    name: String,
    override val risk: Risk = Risk.Low,
    private val reason: String? = null,
    private val run: suspend (JsonObject) -> String = { "ok" },
) : Tool {
    override val descriptor = toolSpec(name, "Fake $name")

    override fun assess(args: JsonObject) = Assessment(listOf(Action(risk, listOf(descriptor.name), reason)))

    override suspend fun execute(args: JsonObject) = run(args)
}

/** Offers [tools] to every Agent's runs, and to the MCP Apps that call them by name. */
class FakeTools(private vararg val tools: FakeTool) : HarnessPlugin {
    override val definition = PluginDefinition("test.tools", "Test tools")

    override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(tools.toList())

    override val apps = object : McpApps {
        override suspend fun resource(agentId: String, ref: McpAppRef) = McpAppResource("<html></html>")
        override suspend fun tool(agentId: String, ref: McpAppRef, name: String) =
            PluginTools(tools.filter { it.descriptor.name == name }).ownedBy(instance).tools.single()
    }
}

/** Offers Agents a host to run shell and files on, so their Workspace settings are available; it has no tools. */
class FakeExec : HarnessPlugin {
    override val definition = PluginDefinition("test.exec", "Test exec")
    override val integrations = mapOf(Integration.Exec to setOf(IntegrationOptions.EXEC_HOST))
}
