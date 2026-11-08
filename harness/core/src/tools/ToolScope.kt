package kinuvo.harness.core.tools

import kinuvo.harness.core.Tolerance

/**
 * The tools one run may use, by local name or group; [All] by default. It narrows an Agent's tools before the model's
 * tier policy and the provider's limits do, so e.g. a larger model can start a run on a small one with `ToolScope.only("web_search")`.
 * Tools named here skip the tier's group filter; the schema level, provider and cap still apply.
 */
data class ToolScope(
    val names: Set<String>? = null,
    val groups: Set<ToolGroup>? = null,
    val toolsets: Set<String>? = null,
    val ceiling: Tolerance? = null,
    val ask: Set<String> = emptySet(),
    private val parents: List<ToolScope> = emptyList(),
) {
    init { toolsets?.forEach { Toolsets.Core.resolve(it) } }

    private val resolved = names.orEmpty().flatMap { if (CoreToolRegistry[it] == null && Toolsets.Core.contains(it)) Toolsets.Core.resolve(it) + it else setOf(it) }.toSet() +
        toolsets.orEmpty().flatMap { Toolsets.Core.resolve(it) }

    fun allows(tool: Tool): Boolean = tool.internal ||
        (tool.coreMetadata?.name in setOf(ToolNames.FIND_TOOLS, ToolNames.DESCRIBE_TOOL, ToolNames.CALL_TOOL) && tool.catalogTargets.any(::allows)) ||
        (parents.all { it.allows(tool) } && ((names == null && groups == null && toolsets == null) ||
            namedHere(tool) || groups?.let { tool.group in it } == true))

    private fun namedHere(tool: Tool) = tool.matchesNames(resolved)
    fun named(tool: Tool): Boolean = allows(tool) && (namedHere(tool) || parents.any { it.named(tool) })
    fun mustAsk(tool: Tool): Boolean = tool.localName in ask || tool.descriptor.name in ask || parents.any { it.mustAsk(tool) }
    fun tolerance(agent: Tolerance): Tolerance = minOf(parents.fold(agent) { limit, parent -> parent.tolerance(limit) }, ceiling ?: agent)

    fun child(requested: ToolScope): ToolScope = ToolScope(parents = listOf(this, requested))

    companion object {
        val All = ToolScope()
        fun only(vararg names: String) = ToolScope(names = names.toSet())
        fun groups(vararg groups: ToolGroup) = ToolScope(groups = groups.toSet())
    }
}

val Tool.localName get() = identity?.localId ?: descriptor.name

internal val Tool.internal get() = coreMetadata?.group == ToolGroup.Internal

internal fun Tool.matchesNames(names: Set<String>): Boolean =
    identity?.key in names || (coreMetadata != null && coreMetadata!!.name in names) ||
        (descriptor.name in names && (coreMetadata != null || CoreToolRegistry[descriptor.name] == null))
