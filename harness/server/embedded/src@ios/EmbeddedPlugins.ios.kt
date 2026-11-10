package kinuvo.harness.server

import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.codex.OpenAIProvider
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.FileAttachments
import kinuvo.harness.koog.compatibleProviders
import kotlinx.io.files.Path

/** What an iPhone's Server runs with; [extra] are plugins the app builds from its own objects, e.g. Apple's on-device model. */
fun embeddedPlugins(home: Path, extra: List<HarnessPlugin> = emptyList()): List<HarnessPlugin> {
    val files = AgentFiles(home)
    return extra + CodexProvider() + OpenAIProvider(files) + sharedPlugins(files) +
        compatibleProviders(files, attachments = FileAttachments(Path(home, "attachments"))::bytes)
}
