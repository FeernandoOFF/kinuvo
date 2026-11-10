package kinuvo.harness.server.testing

import kinuvo.harness.execution.api.AgentConfig
import kinuvo.harness.core.AgentModel
import kinuvo.server.plugin.CustomEndpoints
import kinuvo.harness.core.plugin.ModelProvider
import kinuvo.harness.core.plugin.ModelProviderPlugin
import kinuvo.server.plugin.ProviderApiKey
import kinuvo.server.plugin.ProviderAuth
import kinuvo.protocol.CustomEndpointParams
import kinuvo.protocol.DeviceCode
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.PluginDefinition
import kinuvo.protocol.ProviderAccount
import kinuvo.protocol.ProviderApiKeyStatus
import kinuvo.protocol.ProviderRef
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A model provider that only lists [models]; the Server's scripted model answers. [signedIn] gives it an account, [needsKey]
 * an API key that is not configured, and neither means it needs no sign-in.
 */
class FakeModelProvider(
    override val id: String,
    override val name: String,
    private val models: List<ModelInfo> = emptyList(),
    signedIn: Boolean? = null,
    needsKey: Boolean = false,
) : ModelProvider {
    override suspend fun models() = models

    override fun model(config: AgentConfig): AgentModel = error("The scripted model answers; $id only lists models")

    override val auth = signedIn?.let { signed ->
        object : ProviderAuth {
            override suspend fun account() = ProviderAccount(signed)
            override suspend fun login(show: (DeviceCode) -> Unit) {}
            override suspend fun logout() {}
        }
    }

    override val apiKey = if (needsKey) object : ProviderApiKey {
        override suspend fun status() = ProviderApiKeyStatus(configured = false, storage = "Server keychain")
        override suspend fun save(value: String) {}
    } else null
}

/** Gives a Server the model [providers], and custom endpoints that discover no models, as a Server with none reachable does. */
class FakeModels(private vararg val providers: FakeModelProvider) : ModelProviderPlugin {
    override val definition = PluginDefinition("test.models", "Test models")
    override val modelProviders: List<ModelProvider> get() = providers.toList()
    val discovered = CopyOnWriteArrayList<CustomEndpointParams>()

    override val customEndpoints = object : CustomEndpoints {
        override suspend fun discover(params: CustomEndpointParams): List<ModelInfo> { discovered += params; return emptyList() }
        override suspend fun save(params: CustomEndpointParams) = ProviderRef(params.id)
    }
}
