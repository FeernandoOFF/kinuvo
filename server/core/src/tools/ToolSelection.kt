package kinuvo.server.tools

import kinuvo.server.plugin.Tool
import kinuvo.harness.ToolPolicy
import kinuvo.harness.simplified

/**
 * The tools a run offers, narrowed in turn by [scope], the policy's groups and schema level, the provider's
 * [accepts] and the policy's cap, keeping its [preferred][ToolPolicy.preferred] tools first. A selection no larger than the cap
 * is kept whole. [ToolGroup.Internal] tools are always kept.
 */
fun ToolPolicy.select(tools: List<Tool>, scope: ToolScope = ToolScope.All, accepts: (Tool) -> Boolean = { true }): List<Tool> {
    val (internal, rest) = tools.filter(scope::allows).partition { it.internal }
    val allowed = rest.filter { (it.group in groups && it.coreMetadata?.name !in excluded || scope.named(it)) && it.descriptor.simplified(schema) != null && accepts(it) }
    val limit = cap ?: return tools.filter { it in internal || it in allowed }
    val order = compareBy<Tool> { !scope.named(it) }.thenBy { preferred.indexOf(it.localName).let { i -> if (i < 0) preferred.size else i } }.thenBy { it.localName }
    return internal + allowed.sortedWith(order).take(limit)
}
