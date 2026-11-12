package kinuvo.testing.harness

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.builtin.AgentModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

/** What the scripted model does at one step of a turn: call a tool, or answer and end the turn. */
sealed interface ScriptedStep {
    data class ToolCall(val name: String, val args: String) : ScriptedStep
    data class Reply(val text: String) : ScriptedStep
}

class ScriptedTurn {
    internal val steps = mutableListOf<ScriptedStep>()

    fun toolCall(name: String, args: String = "{}") { steps += ScriptedStep.ToolCall(name, args) }

    fun reply(text: String) { steps += ScriptedStep.Reply(text) }
}

/**
 * A model that answers each user message from a script: the first [on] rule whose text the latest user message contains
 * plays its steps in order, one per model call (a tool call's result brings the next step); otherwise it replies [fallback].
 */
class ModelScript(var fallback: String = "Scripted reply") {
    private class Rule(val text: String?, val steps: List<ScriptedStep>)

    private val rules = MutableStateFlow(emptyList<Rule>())
    private val seen = MutableStateFlow(emptyList<Prompt>())

    /** Every prompt the model received, oldest first. */
    val prompts: List<Prompt> get() = seen.value

    val model: AgentModel = scriptedModel { prompt, _ -> respond(prompt) }

    /** Plays [turn] for user messages containing [text], or for any message when [text] is null. */
    fun on(text: String? = null, turn: ScriptedTurn.() -> Unit) {
        rules.update { it + Rule(text, ScriptedTurn().apply(turn).steps.toList()) }
    }

    fun reply(text: String) { fallback = text }

    private fun respond(prompt: Prompt): Flow<StreamFrame> {
        seen.update { it + prompt }
        val messages = prompt.messages
        val last = messages.indexOfLast { it is Message.User && it.parts.none { part -> part is MessagePart.Tool.Result } }
        val user = messages.getOrNull(last)?.textContent().orEmpty()
        val answered = messages.drop(last + 1).toolResults.size
        val steps = rules.value.firstOrNull { it.text == null || it.text in user }?.steps.orEmpty()
        return when (val step = steps.getOrNull(answered) ?: ScriptedStep.Reply(fallback)) {
            is ScriptedStep.Reply -> flowOf(StreamFrame.TextDelta(step.text), StreamFrame.End())
            is ScriptedStep.ToolCall -> flowOf(StreamFrame.ToolCallComplete("call-$answered", step.name, step.args), StreamFrame.End())
        }
    }
}
