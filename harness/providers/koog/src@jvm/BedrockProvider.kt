package kinuvo.harness.koog

import ai.koog.prompt.executor.clients.bedrock.BedrockAPIMethod
import ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.llm.LLMProvider
import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.smithy.kotlin.runtime.net.url.Url
import kinuvo.harness.core.*
import kinuvo.harness.core.store.*
import kinuvo.protocol.Attachment
import kinuvo.protocol.ModelInfo

/** AWS credentials use the SDK's default chain, including profiles, environment and workload roles. */
fun bedrockProviders(files: AgentFiles, attachments: ((Attachment) -> ByteArray)? = null): List<HarnessPlugin> =
    files.config().providers.filter { (id, options) -> id == "bedrock" || options.type == "bedrock" }.map { (id, options) ->
        require(id.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "Invalid model provider ID: $id" }
        BedrockProvider(id, options, files.loadEnv(), attachments)
    }

private class BedrockProvider(
    override val id: String,
    private val options: ModelProviderConfig,
    private val env: Map<String, String>,
    private val attachments: ((Attachment) -> ByteArray)?,
) : ModelProvider, HarnessPlugin {
    override val name = options.name ?: "Amazon Bedrock"
    override val catalogId = "amazon-bedrock"
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.provider.$id", name)
    override val modelProviders get() = listOf(this)
    private val executor = lazy {
        koogExecutor(BedrockLLMClient(BedrockRuntimeClient {
            region = options.region ?: env["AWS_REGION"] ?: env["AWS_DEFAULT_REGION"] ?: "us-east-1"
            options.baseUrl?.let { endpointUrl = Url.parse(endpoint(it)) }
        }, apiMethod = BedrockAPIMethod.Converse))
    }

    override fun usableTools(model: String, tools: List<ai.koog.agents.core.tools.ToolDescriptor>) =
        tools.filterNot { it is kinuvo.harness.core.tools.RawSchemaToolDescriptor }
    override suspend fun models() = options.models.ifEmpty { BedrockModels.models.chatModels().map { it.info() } }
    override fun model(config: AgentConfig) = model(config, null)
    override fun model(config: AgentConfig, info: ModelInfo?) = AgentModel(executor.value,
        (info ?: options.models.find { it.id == config.model } ?: ModelInfo(config.model)).llModel(LLMProvider.Bedrock), attachments = attachments, reasoningInContent = true, reasoningFormat = "bedrock")
    override suspend fun close() { if (executor.isInitialized()) executor.value.close() }
}
