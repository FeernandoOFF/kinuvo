package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kinuvo.protocol.AgentRef
import kinuvo.protocol.Api
import kinuvo.protocol.CreateAgentParams
import kinuvo.protocol.UpdateAgentParams

internal class AgentCommands : Group(
    "agents", "List, create, change and delete Agents (Hermes: profile).",
    ListAgents(), ShowAgent(), CreateAgent(), SetAgent(), DeleteAgent(),
)

private class ListAgents : ServerCommand("list", "List the Agents.") {
    override suspend fun run() {
        val agents = session.call { client.call(Api.Agents, Unit) }
        echo(table(listOf("ID", "NAME", "EXEC"), agents.map { listOf(it.id, it.name, it.exec) }))
    }
}

private class ShowAgent : ServerCommand("show", "Show an Agent's settings, Identity and Soul.") {
    private val id by argument("ID")

    override suspend fun run() {
        val a = session.call { client.call(Api.Agent, AgentRef(id)) }
        val s = a.settings
        echo(table(listOf("ID", a.info.id), listOf(
            listOf("name", a.info.name),
            listOf("model", s.model),
            listOf("reasoning", s.reasoning),
            listOf("approvals", s.approvals),
            listOf("exec", s.exec),
            listOf("workspace", s.workspace.joinToString(",").ifEmpty { "-" }),
            listOf("tools", s.tools.joinToString(",")),
        )))
        echo("\n--- IDENTITY.md\n${a.identity.trim()}\n\n--- SOUL.md\n${a.soul.trim()}")
    }
}

private class CreateAgent : ServerCommand("create", "Create an Agent from the default template, or copy another one.") {
    private val id by argument("ID", help = "Lowercase letters, digits and hyphens")
    private val name by option("--name", help = "Display name (the title of IDENTITY.md)")
    private val clone by option("--clone", metavar = "FROM", help = "Copy this Agent's settings, Identity, Soul, Skills and MCP servers")

    override suspend fun run() {
        val agent = session.call { client.call(Api.CreateAgent, CreateAgentParams(id, name, clone)) }
        echo("Created agent '${agent.id}' (${agent.name}).")
    }
}

private class SetAgent : ServerCommand("set", "Change one setting: name, model, reasoning, approvals, exec, workspace, tools, identity or soul.") {
    private val id by argument("ID")
    private val key by argument("KEY").choice("name", "model", "reasoning", "approvals", "exec", "workspace", "tools", "identity", "soul")
    private val value by argument("VALUE", help = "Comma-separated for workspace and tools; - reads stdin")

    override suspend fun run() {
        val v = textOrStdin(value)
        val list = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val p = UpdateAgentParams(id)
        val params = when (key) {
            "name" -> p.copy(name = v)
            "model" -> p.copy(model = v)
            "reasoning" -> p.copy(reasoning = v)
            "approvals" -> p.copy(approvals = v)
            "exec" -> p.copy(exec = v)
            "workspace" -> p.copy(workspace = list)
            "tools" -> p.copy(tools = list)
            "identity" -> p.copy(identity = v)
            else -> p.copy(soul = v)
        }
        session.call { client.call(Api.UpdateAgent, params) }
        echo("Updated $key of '$id'.")
    }
}

private class DeleteAgent : ServerCommand("delete", "Delete an Agent with all its Sessions, Memory, Skills and Routines.") {
    private val id by argument("ID")
    private val yes by option("-y", "--yes", help = "Don't ask for confirmation").flag()

    override suspend fun run() {
        if (!yes && !confirm("Delete agent '$id' and all its data?")) throw CliktError("Cancelled")
        session.call { client.call(Api.DeleteAgent, AgentRef(id)) }
        echo("Deleted agent '$id'.")
    }
}
