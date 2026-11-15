package kinuvo.server.embedded

import kinuvo.providers.codex.CodexAuth
import kinuvo.server.plugin.ServerPlugin
import kinuvo.server.plugin.Keychain
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.FileAttachments
import kinuvo.server.system
import kinuvo.server.providers.plus
import kotlinx.io.files.Path
import kinuvo.server.providers.ModelProviders
import kinuvo.harness.builtin.plugin.ModelSource

/**
 * What an iPhone's Server runs with; [extra] are providers the app builds from its own objects, e.g. Apple's on-device model,
 * which come first, and [plugins] are other plugins it builds the same way, e.g. Apple's frameworks.
 */
fun embeddedPlugins(home: Path, extra: ModelProviders<ModelSource> = ModelProviders(), plugins: List<ServerPlugin> = emptyList()): EmbeddedPlugins {
    val files = AgentFiles(home)
    val keys = embeddedKeys(files)
    val attachments = FileAttachments(Path(home, "attachments"))::bytes
    val codexAuth = CodexAuth(checkNotNull(Keychain.system("kinuvo", "codex")) { "The iOS Keychain isn't set up" })
    val providers = extra + sharedProviders(files, keys, codexAuth, attachments)
    return EmbeddedPlugins(providers.plugins + sharedPlugins(files) + plugins, providers.sources)
}
