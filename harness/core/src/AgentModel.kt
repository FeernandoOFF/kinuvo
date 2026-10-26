package kinuvo.harness.core

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kinuvo.protocol.Attachment

/** A provider's executor and the native model and parameters for an Agent's requests. */
data class AgentModel(
    val executor: PromptExecutor,
    val model: LLModel,
    val parameters: (String) -> LLMParams = { LLMParams() },
    val attachments: ((Attachment) -> ByteArray)? = null,
    val instructionsInPrompt: Boolean = true,
    val reasoningInContent: Boolean = false,
    val reasoningFormat: String = "openai-responses",
    val failure: (Throwable) -> Throwable = { it },
)
