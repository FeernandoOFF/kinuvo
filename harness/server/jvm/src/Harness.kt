package kinuvo.harness.server

import kinuvo.harness.core.*
import kinuvo.harness.core.store.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kinuvo.protocol.currentPlatform

class Harness private constructor(home: Path, dependencies: RuntimeDependencies, modelFactory: ((AgentConfig) -> Model)?) : HarnessRuntime(
    dependencies, modelFactory,
    platformDescription = "${currentPlatform.displayName} ${System.getProperty("os.arch")}",
) {
    val files = AgentFiles(home)
    constructor(home: Path = kinuvoHome(), store: Store? = null, providers: List<HarnessPlugin>? = null,
        modelFactory: ((AgentConfig) -> Model)? = null, clock: () -> Long = System::currentTimeMillis,
        plugins: List<HarnessPlugin>? = null, secretProviders: List<SecretProvider>? = null,
    ) : this(home, install(home, store, providers, plugins, secretProviders, clock), modelFactory)

    private companion object {
        private fun install(home: Path, store: Store?, providers: List<HarnessPlugin>?, plugins: List<HarnessPlugin>?, secretProviders: List<SecretProvider>?, clock: () -> Long): RuntimeDependencies = runBlocking {
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
                secrets = secrets, userHome = Path(System.getProperty("user.home")), clock = clock)
        }
    }
}
