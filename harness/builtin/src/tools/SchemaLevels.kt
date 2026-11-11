package kinuvo.harness.builtin.tools

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.SchemaLevel
import kinuvo.harness.simplified

/** [this] as sent to a model that takes schemas up to [level]: parameters beyond it are dropped, and null when a required one is. */
fun ToolDescriptor.simplified(level: SchemaLevel): ToolDescriptor? = toSpec().simplified(level)?.toDescriptor()
