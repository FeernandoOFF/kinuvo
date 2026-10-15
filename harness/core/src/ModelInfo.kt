package kinuvo.harness.core

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kinuvo.protocol.ModelInfo

/** The resolved catalog entry also configures the model client. Unknown fields retain provider defaults. */
fun ModelInfo.llModel(provider: LLMProvider, endpoints: List<LLMCapability> = emptyList()) = LLModel(
    provider = provider,
    id = id,
    capabilities = buildList {
        add(LLMCapability.Completion)
        if (tools != false) add(LLMCapability.Tools)
        if (reasoning != emptyList<String>()) add(LLMCapability.Thinking)
        if (input?.contains("image") == true) add(LLMCapability.Vision.Image)
        if (input?.contains("file") == true || input?.contains("document") == true) add(LLMCapability.Document)
        addAll(endpoints)
    },
    contextLength = context?.toLong(),
)
