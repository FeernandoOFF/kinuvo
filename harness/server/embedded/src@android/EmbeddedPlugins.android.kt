package kinuvo.harness.server

import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.codex.OpenAIProvider
import kinuvo.harness.core.plugin.HarnessPlugin
import kinuvo.harness.core.PrivateFile
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.FileAttachments
import kinuvo.harness.koog.compatibleProviders
import kinuvo.harness.litert.LiteRtProvider
import java.io.File
import kotlinx.io.files.Path

/** What an Android phone's Server runs with; [extra] are plugins the app builds from its own objects, e.g. its WebView browser. */
fun embeddedPlugins(home: Path, extra: List<HarnessPlugin> = emptyList()): List<HarnessPlugin> {
    val files = AgentFiles(home)
    val attachments = FileAttachments(Path(home, "attachments"))::bytes
    return listOf(CodexProvider(CodexAuth(PrivateFile(Path(home, "codex-auth.json"))), attachments), OpenAIProvider(files, attachments = attachments)) +
        sharedPlugins(files) + extra + compatibleProviders(files, attachments = attachments) + LiteRtProvider(File(home.toString()))
}
