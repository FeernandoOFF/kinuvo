package kinuvo.server.embedded

import kinuvo.plugins.browser.BrowserPlugin
import kinuvo.providers.codex.CodexAuth
import kinuvo.providers.codex.CodexProvider
import kinuvo.providers.codex.OpenAIProvider
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.PrivateFile
import kinuvo.harness.builtin.plugin.ModelSource
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.FileAttachments
import kinuvo.providers.koog.compatibleProviders
import kinuvo.providers.litert.LiteRtProvider
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

/** The browser plugin over the app's WebView when this device supports it; call on the main thread. */
fun AndroidBrowserRuntime.browserPlugins(): List<HarnessPlugin> = if (available) listOf(BrowserPlugin(this)) else emptyList()
