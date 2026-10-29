package kinuvo.harness.server

import kinuvo.harness.anytype.AnytypePlugin
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.codex.OpenAIProvider
import kinuvo.harness.koog.compatibleProviders
import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.SecretProvider
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.loadEnv
import kinuvo.harness.exec.ExecPlugin
import kinuvo.harness.executor.ExecutorPlugin
import kinuvo.harness.firecrawl.FirecrawlPlugin
import kinuvo.harness.mcp.McpPlugin
import kinuvo.harness.ntfy.NtfyPlugin
import kinuvo.harness.protonpass.ProtonPass
import kinuvo.harness.reminders.RemindersPlugin

/** The model providers a JVM host offers. */
fun jvmProviders(files: AgentFiles, secrets: Secrets = Secrets(), installed: (HarnessPlugin) -> Unit = {}): List<HarnessPlugin> {
    val attachments = kinuvo.harness.core.store.FileAttachments(kotlinx.io.files.Path(files.home, "attachments"))::bytes
    return (listOf(CodexProvider(attachments = attachments), OpenAIProvider(files, secrets, attachments)) +
        compatibleProviders(files, secrets, attachments)).onEach(installed)
}

/** What a JVM host (desktop, the `kinuvo` command) runs with. */
fun jvmPlugins(files: AgentFiles, secrets: Secrets, providers: List<HarnessPlugin> = jvmProviders(files, secrets), installed: (HarnessPlugin) -> Unit = {}): List<HarnessPlugin> =
    providers.onEach(installed) + listOf(
        { kinuvo.harness.browser.BrowserPlugin(java.io.File(files.home.toString(), "browser")) },
        { ExecPlugin(files, secrets) }, { McpPlugin(files, secrets) }, { RemindersPlugin() },
        { AnytypePlugin(files, secrets) }, { FirecrawlPlugin() }, { NtfyPlugin() }, { ExecutorPlugin() },
    ).map { it().also(installed) }

/** The password managers Secret references can point into. */
fun jvmSecretProviders(files: AgentFiles): List<SecretProvider> =
    listOf(ProtonPass(java.io.File(files.home.toString(), "protonpass")) { files.loadEnv(process = emptyMap()) })
