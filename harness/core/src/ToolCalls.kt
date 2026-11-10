package kinuvo.harness.core

import kinuvo.harness.execution.api.ToolGate
import kinuvo.harness.execution.api.ToolProvider
import kinuvo.harness.execution.api.ToolSpec
import kinuvo.harness.execution.api.ToolTurn
import kinuvo.protocol.SessionEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the loop knows of a turn's tools, and its only way to run them: through the [ToolGate]. */
internal class ToolCalls(provider: ToolProvider, private val gate: ToolGate, private val turn: ToolTurn) {
    private val specs: Map<String, ToolSpec> = provider.tools.associateBy { it.name }.let { named ->
        named + provider.tools.flatMap { spec -> spec.aliases.map { it to spec } }.filter { it.first !in named }
    }

    val executedCalls: Int get() = turn.executedCalls

    fun search(name: String, args: String): Boolean {
        if (specs[name]?.search != true) return false
        val action = runCatching { Json.parseToJsonElement(args).jsonObject["action"]?.jsonPrimitive?.content }.getOrNull()
        return action == null || action == "search"
    }

    fun internal(name: String) = specs[name]?.internal == true

    fun repeatable(name: String) = specs[name]?.repeatable ?: true

    suspend fun call(call: SessionEntry.ToolCall): SessionEntry.ToolResult = gate.invoke(call, turn)
}
