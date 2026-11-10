package kinuvo.harness.core.tools

import kinuvo.protocol.ToolNames
import kinuvo.server.plugin.CoreToolRegistry

data class Toolset(val name: String, val tools: Set<String> = emptySet(), val includes: Set<String> = emptySet())

class Toolsets(val entries: List<Toolset>) {
    private val byName = entries.associateBy { it.name }.also { require(it.size == entries.size) }

    init { entries.forEach { resolve(it.name) } }

    fun resolve(name: String): Set<String> {
        fun visit(name: String, path: Set<String>): Set<String> {
            require(name !in path) { "Toolset cycle: $path -> $name" }
            val entry = requireNotNull(byName[name]) { "Unknown toolset: $name" }
            return entry.tools + entry.includes.flatMap { visit(it, path + name) }
        }
        return visit(name, emptySet())
    }

    fun contains(name: String) = name in byName

    companion object {
        fun legacyGroups(name: String): List<String> {
            if (name in ToolNames.OPTIONAL || !Core.contains(name)) return listOf(name)
            val tools = Core.resolve(name)
            return ToolNames.OPTIONAL.filter { option -> option in tools || (Core.contains(option) && Core.resolve(option).any { it in tools }) }
        }

        val Core = Toolsets(
            CoreToolRegistry.entries.groupBy { it.group }.map { (group, tools) ->
                Toolset(group.name.lowercase(), tools.map { it.name }.toSet())
            }.filter { it.name !in setOf("core", "skills") } + listOf(
                Toolset("core", setOf("memory"), setOf("web")),
                Toolset("browser", CoreToolRegistry.entries.filter { it.name.startsWith("browser_") }.map { it.name }.toSet()),
                Toolset("device_tools", setOf("device_tools", "contacts_lookup", "workouts_list", "sleep_sessions")),
                Toolset("web", setOf("web_fetch", "web_search")),
                Toolset("web_fetch", includes = setOf("web")),
                Toolset("skills", setOf("activate_skill", "read_skill_file", "skill_manage")),
                Toolset("knowledge", setOf("knowledge", "knowledge_rewrite", "conventions")),
                Toolset("computer", CoreToolRegistry.entries.filter { it.name.startsWith("computer_") || it.name == "request_takeover" }.map { it.name }.toSet()),
            ),
        )
    }
}
