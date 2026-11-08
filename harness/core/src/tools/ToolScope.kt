package kinuvo.harness.core.tools

/**
 * The tools one run may use, by local name or group; [All] by default. It narrows an Agent's tools before the model's
 * tier policy and the provider's limits do, so e.g. a larger model can start a run on a small one with `ToolScope.only("web_search")`.
 * Tools named here skip the tier's group filter; the schema level, provider and cap still apply.
 */
data class ToolScope(val names: Set<String>? = null, val groups: Set<ToolGroup>? = null) {
    fun allows(tool: Tool): Boolean = tool.group == ToolGroup.Internal || (names == null && groups == null) ||
        named(tool) || groups?.let { tool.group in it } == true

    fun named(tool: Tool): Boolean = names?.let { tool.localName in it || tool.descriptor.name in it } == true

    companion object {
        val All = ToolScope()
        fun only(vararg names: String) = ToolScope(names = names.toSet())
        fun groups(vararg groups: ToolGroup) = ToolScope(groups = groups.toSet())
    }
}

val Tool.localName get() = identity?.localId ?: descriptor.name
