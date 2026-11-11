package kinuvo.server.embedded

import kinuvo.providers.codex.CodexAuth
import kinuvo.providers.codex.CodexProvider
import kinuvo.providers.codex.OpenAIProvider
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.ModelProviders
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.FileAttachments
import kinuvo.server.system
import kinuvo.providers.koog.compatibleProviders
import kotlinx.io.files.Path

/** What an iPhone's Server runs with; [extra] are providers the app builds from its own objects, e.g. Apple's on-device model, which come first. */
fun embeddedPlugins(home: Path, extra: ModelProviders = ModelProviders()): EmbeddedPlugins {
    val files = AgentFiles(home)
    val keys = embeddedKeys(files)
    val attachments = FileAttachments(Path(home, "attachments"))::bytes
    val codexAuth = CodexAuth(checkNotNull(Keychain.system("kinuvo", "codex")) { "The iOS Keychain isn't set up" })
    val endpoints = compatibleProviders(keys, files, attachments)
    val codex = CodexProvider(codexAuth)
    val openAI = OpenAIProvider(keys)
    return EmbeddedPlugins(extra.plugins + listOf(codex, openAI) + sharedPlugins(files) + endpoints) { extra.sources() + listOf(codex, openAI) + endpoints.modelSources }
}
