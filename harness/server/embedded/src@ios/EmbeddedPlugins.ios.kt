package kinuvo.harness.server

import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.codex.OpenAIProvider
import kinuvo.server.plugin.Keychain
import kinuvo.harness.core.plugin.ModelProviders
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.FileAttachments
import kinuvo.harness.core.system
import kinuvo.harness.koog.compatibleProviders
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
