package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.SchemaLevel
import kinuvo.harness.core.ToolPolicy

/**
 * The tools a run offers, narrowed in turn by [scope], the policy's groups and schema level, the provider's
 * [accepts] and the policy's cap, keeping its [preferred][ToolPolicy.preferred] tools first. A selection no larger than the cap
 * is kept whole. [ToolGroup.Internal] tools are always kept.
 */
fun ToolPolicy.select(tools: List<Tool>, scope: ToolScope = ToolScope.All, accepts: (Tool) -> Boolean = { true }): List<Tool> {
    val (internal, rest) = tools.filter(scope::allows).partition { it.internal }
    val allowed = rest.filter { (it.group in groups || scope.named(it)) && it.descriptor.simplified(schema) != null && accepts(it) }
    if (cap == null) return tools.filter { it in internal || it in allowed }
    val order = compareBy<Tool> { !scope.named(it) }.thenBy { preferred.indexOf(it.localName).let { i -> if (i < 0) preferred.size else i } }.thenBy { it.localName }
    return internal + allowed.sortedWith(order).take(cap)
}

/** [this] as sent to a model that takes schemas up to [level]: parameters beyond it are dropped, and null when a required one is. */
fun ToolDescriptor.simplified(level: SchemaLevel): ToolDescriptor? {
    if (level == SchemaLevel.Full) return this
    if (this is RawSchemaToolDescriptor) return null
    val fits = { p: ToolParameterDescriptor -> p.type.fits(level) }
    if (!requiredParameters.all(fits)) return null
    val optional = optionalParameters.filter(fits)
    return if (optional.size == optionalParameters.size) this else ToolDescriptor(name, description, requiredParameters, optional)
}

private fun ToolParameterType.fits(level: SchemaLevel): Boolean = when (this) {
    ToolParameterType.String, ToolParameterType.Integer, ToolParameterType.Float, ToolParameterType.Boolean, is ToolParameterType.Enum -> true
    is ToolParameterType.List -> itemsType !is ToolParameterType.List && itemsType.fits(level)
    is ToolParameterType.Object -> level == SchemaLevel.OneLevel && properties.all { it.type.fits(SchemaLevel.Flat) }
    else -> false
}
