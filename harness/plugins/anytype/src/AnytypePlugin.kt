package kinuvo.harness.anytype

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.SecretResolver
import kinuvo.server.plugin.PluginStorage
import kinuvo.protocol.PluginDefinition

class AnytypePlugin(files: PluginStorage, secrets: SecretResolver = SecretResolver.None) : HarnessPlugin {
    override val definition = PluginDefinition("kinuvo.anytype", "Anytype")
    private val client = HttpClient { install(HttpTimeout) { requestTimeoutMillis = 30_000 } }
    override val knowledgeSources = listOf(
        AnytypeKnowledgeSource({ files.knowledgeOptions() }, client) { secrets.expand(it, files.loadEnv(), "Kinuvo: Anytype knowledge") },
    )
    override suspend fun close() { client.close() }
}
