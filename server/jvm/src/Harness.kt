package kinuvo.server.jvm

import kinuvo.harness.AgentConfig
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.SecretProvider
import kinuvo.harness.builtin.plugin.ModelSource
import kinuvo.server.plugin.*
import kinuvo.harness.builtin.AgentModel
import kinuvo.harness.builtin.NativeExecutionFactory
import kinuvo.server.*
import kinuvo.server.store.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kinuvo.protocol.currentPlatform
import kinuvo.providers.codex.CodexAuth
import kinuvo.providers.codex.CodexRealtime
import kinuvo.server.plugin.VoiceProviders
import kinuvo.server.providers.ModelProviders

class Harness private constructor(home: Path, installed: Installed, modelFactory: ((AgentConfig) -> AgentModel)?) : HarnessRuntime(
    installed.dependencies, { NativeExecutionFactory(it, installed.sources, modelFactory) },
    platformDescription = "${currentPlatform.displayName} ${System.getProperty("os.arch")}",
    unmanagedModels = modelFactory != null,
) {
    val files = AgentFiles(home)
    constructor(home: Path = kinuvoHome(), store: Store? = null, providers: ModelProviders<ModelSource>? = null,
        modelFactory: ((AgentConfig) -> AgentModel)? = null, clock: () -> Long = System::currentTimeMillis,
        plugins: List<HarnessPlugin>? = null, secretProviders: List<SecretProvider>? = null, codexAuth: CodexAuth = CodexAuth(),
    ) : this(home, install(home, store, providers, plugins, secretProviders, clock, codexAuth), modelFactory)

    private class Installed(val dependencies: RuntimeDependencies, val sources: () -> List<ModelSource>)

    private companion object {
        private fun install(home: Path, store: Store?, providers: ModelProviders<ModelSource>?, plugins: List<HarnessPlugin>?, secretProviders: List<SecretProvider>?, clock: () -> Long, auth: CodexAuth): Installed = runBlocking {
            val files = AgentFiles(home)
            val secrets = Secrets(secretProviders ?: jvmSecretProviders(files))
            val owned = mutableListOf<HarnessPlugin>()
            val (sources, selected) = try {
                if (plugins != null) (providers?.sources ?: { emptyList() }) to plugins
                else (providers ?: jvmProviders(files, secrets, owned::add)).let { it.sources to jvmPlugins(files, secrets, it, owned::add) }
            } catch (e: Throwable) {
                ServerResources(owned).close()
                throw e
            }
            ServerInstallation.assemble(files, store, selected, modelSources = sources,
                secrets = secrets, userHome = Path(System.getProperty("user.home")), clock = clock).also { dependencies ->
                    if (auth.account().signedIn && CodexRealtime.available()) {
                        val voice = dependencies.voice
                        dependencies.voice = VoiceProviders(voice.transcriber, voice.processor, voice.synthesizer, CodexRealtime(auth))
                    }
                }.let { Installed(it, sources) }
        }
    }
}
