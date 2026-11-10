package kinuvo.harness.server

import kinuvo.harness.execution.api.AgentConfig
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.SecretProvider
import kinuvo.harness.core.plugin.*
import kinuvo.harness.core.*
import kinuvo.harness.core.store.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kinuvo.protocol.currentPlatform
import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.codex.CodexRealtime
import kinuvo.server.plugin.VoiceProviders

class Harness private constructor(home: Path, dependencies: RuntimeDependencies, modelFactory: ((AgentConfig) -> AgentModel)?) : HarnessRuntime(
    dependencies, modelFactory,
    platformDescription = "${currentPlatform.displayName} ${System.getProperty("os.arch")}",
) {
    val files = AgentFiles(home)
    constructor(home: Path = kinuvoHome(), store: Store? = null, providers: List<ModelProviderPlugin>? = null,
        modelFactory: ((AgentConfig) -> AgentModel)? = null, clock: () -> Long = System::currentTimeMillis,
        plugins: List<HarnessPlugin>? = null, secretProviders: List<SecretProvider>? = null, codexAuth: CodexAuth = CodexAuth(),
    ) : this(home, install(home, store, providers, plugins, secretProviders, clock, codexAuth), modelFactory)

    private companion object {
        private fun install(home: Path, store: Store?, providers: List<ModelProviderPlugin>?, plugins: List<HarnessPlugin>?, secretProviders: List<SecretProvider>?, clock: () -> Long, auth: CodexAuth): RuntimeDependencies = runBlocking {
            val files = AgentFiles(home)
            val secrets = Secrets(secretProviders ?: jvmSecretProviders(files))
            val owned = mutableListOf<HarnessPlugin>()
            val selected = try {
                plugins ?: jvmPlugins(files, secrets, providers ?: jvmProviders(files, secrets, owned::add), owned::add)
            } catch (e: Throwable) {
                ServerResources(owned).close()
                throw e
            }
            ServerInstallation.assemble(files, store, selected,
                secrets = secrets, userHome = Path(System.getProperty("user.home")), clock = clock).also { dependencies ->
                    if (auth.account().signedIn && CodexRealtime.available()) {
                        val voice = dependencies.voice
                        dependencies.voice = VoiceProviders(voice.transcriber, voice.processor, voice.synthesizer, CodexRealtime(auth))
                    }
                }
        }
    }
}
