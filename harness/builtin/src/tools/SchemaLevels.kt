package kinuvo.harness.builtin.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.builtin.SchemaLevel

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
