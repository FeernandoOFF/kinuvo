package kinuvo.harness.testing

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList

fun scriptedModel(respond: (Prompt, List<ToolDescriptor>) -> Flow<StreamFrame>) = AgentModel(
    ScriptedExecutor(respond),
    LLModel(LLMProvider.OpenAI, "scripted", listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.Thinking)),
    attachments = { ByteArray(it.size) },
)

class ScriptedExecutor(private val respond: (Prompt, List<ToolDescriptor>) -> Flow<StreamFrame>) : PromptExecutor() {
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = respond(prompt, tools)
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val frames = respond(prompt, tools).toList()
        return Message.Assistant(frames.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text },
            frames.filterIsInstance<StreamFrame.End>().lastOrNull()?.metaInfo ?: ResponseMetaInfo.Empty)
    }
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("No moderation scripted")
    override fun close() {}
}

val List<Message>.toolResults: List<MessagePart.Tool.Result> get() = flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
val List<Message>.toolCalls: List<MessagePart.Tool.Call> get() = flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Call>()

fun AgentModel.request(instructions: String, history: List<kinuvo.protocol.SessionEntry>, tools: List<ToolDescriptor>) =
    executor.executeStreaming(kinuvo.harness.core.SessionMessages(this).prompt(instructions, history), model, tools.sortedBy { it.name })
        .catch { throw failure(it) }
