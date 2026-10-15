package kinuvo.harness.anytype

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.knowledgeConfig
import kinuvo.harness.core.store.loadEnv

class AnytypePlugin(files: AgentFiles, secrets: Secrets = Secrets()) : HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.anytype", "Anytype")
    private val client = HttpClient(Java) { install(HttpTimeout) { requestTimeoutMillis = 30_000 } }
    override val knowledgeSources = listOf(
        AnytypeKnowledgeSource({ files.knowledgeConfig().options }, client) { secrets.expand(it, files.loadEnv(), "Kinuvo: Anytype knowledge") },
    )
    override suspend fun close() { client.close() }
}
