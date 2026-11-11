package kinuvo.harness.server

import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.codex.OpenAIProvider
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.PrivateFile
import kinuvo.harness.core.plugin.ModelSource
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.FileAttachments
import kinuvo.harness.koog.compatibleProviders
import kinuvo.harness.litert.LiteRtProvider
import java.io.File
import kotlinx.io.files.Path

/** What an Android phone's Server runs with; [extra] are plugins the app builds from its own objects, e.g. its WebView browser. */
fun embeddedPlugins(home: Path, extra: List<HarnessPlugin> = emptyList()): EmbeddedPlugins {
    val files = AgentFiles(home)
    val keys = embeddedKeys(files)
    val attachments = FileAttachments(Path(home, "attachments"))::bytes
    val endpoints = compatibleProviders(keys, files, attachments)
    val codex = CodexProvider(CodexAuth(PrivateFile(Path(home, "codex-auth.json"))), attachments)
    val openAI = OpenAIProvider(keys, attachments)
    val local = LiteRtProvider(File(home.toString()))
    return EmbeddedPlugins(listOf<HarnessPlugin>(codex, openAI) + sharedPlugins(files) + extra + endpoints + local) { listOf<ModelSource>(codex, openAI) + endpoints.modelSources + local }
}
