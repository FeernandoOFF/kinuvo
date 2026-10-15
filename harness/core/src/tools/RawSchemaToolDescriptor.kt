package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.serialization.json.JsonObject

/** A tool whose JSON Schema is used as-is instead of being rebuilt from Koog parameter types. e.g. external MCPs */
class RawSchemaToolDescriptor(name: String, description: String, val schema: JsonObject) : ToolDescriptor(name, description)
