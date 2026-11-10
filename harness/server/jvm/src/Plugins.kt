package kinuvo.harness.server

import kinuvo.harness.anytype.AnytypePlugin
import kinuvo.harness.apple.fmProvider
import kinuvo.harness.bitwarden.Bitwarden
import kinuvo.harness.browser.BrowserPlugin
import kinuvo.harness.caldav.CalDavPlugin
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.codex.OpenAIProvider
import kinuvo.harness.koog.compatibleProviders
import kinuvo.harness.litert.LiteRtProvider
import kinuvo.harness.core.plugin.ModelProviderPlugin
import kinuvo.harness.core.system
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.SecretProvider
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.FileAttachments
import kinuvo.harness.exec.ExecPlugin
import kinuvo.harness.executor.ExecutorPlugin
import kinuvo.harness.firecrawl.FirecrawlPlugin
import kinuvo.harness.mcp.McpPlugin
import kinuvo.harness.ntfy.NtfyPlugin
import kinuvo.harness.onepassword.OnePassword
import kinuvo.harness.protonpass.ProtonPass
import kinuvo.harness.reminders.RemindersPlugin
import kinuvo.harness.todoist.TodoistPlugin
import kinuvo.harness.voice.DownloadedVoice
import java.io.File
import kotlinx.io.files.Path

/** The model providers a JVM host offers. */
fun jvmProviders(files: AgentFiles, secrets: Secrets = Secrets(), installed: (HarnessPlugin) -> Unit = {}): List<ModelProviderPlugin> {
    val attachments = FileAttachments(Path(files.home, "attachments"))::bytes
    return (listOf(CodexProvider(attachments = attachments), OpenAIProvider(files, secrets, attachments)) +
        compatibleProviders(files, secrets, attachments) + listOfNotNull(fmProvider()) + LiteRtProvider(File(files.home.toString()))).onEach(installed)
}

/** What a JVM host (desktop, the `kinuvo` command) runs with. */
fun jvmPlugins(files: AgentFiles, secrets: Secrets, providers: List<ModelProviderPlugin> = jvmProviders(files, secrets), installed: (HarnessPlugin) -> Unit = {}): List<HarnessPlugin> =
    providers.onEach(installed) + listOf(
        { BrowserPlugin(File(files.home.toString(), "browser")) },
        { ExecPlugin(files, secrets) }, { McpPlugin(files, secrets, Keychain.system("kinuvo", "mcp-oauth")) }, { RemindersPlugin() }, { TodoistPlugin() }, { CalDavPlugin() },
        { AnytypePlugin(files, secrets) }, { FirecrawlPlugin() }, { NtfyPlugin() }, { ExecutorPlugin() }, { DownloadedVoice(File(files.home.toString())) },
    ).map { it().also(installed) }

/** The password managers Secret references can point into. */
fun jvmSecretProviders(files: AgentFiles): List<SecretProvider> =
    listOf(
        ProtonPass(File(files.home.toString()), { files.loadEnv(process = emptyMap()) }, Keychain.system("kinuvo", "protonpass")),
        OnePassword(File(files.home.toString()), { files.loadEnv(process = emptyMap()) }, Keychain.system("kinuvo", "1password")),
        Bitwarden(File(files.home.toString()), { files.loadEnv(process = emptyMap()) }, Keychain.system("kinuvo", "bitwarden")),
    )
