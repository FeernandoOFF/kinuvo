package kinuvo.server.jvm

import kinuvo.plugins.anytype.AnytypePlugin
import kinuvo.plugins.apple.ApplePlugin
import kinuvo.plugins.apple.loadAppleSystem
import kinuvo.providers.apple.fmProvider
import kinuvo.plugins.bitwarden.Bitwarden
import kinuvo.plugins.browser.BrowserPlugin
import kinuvo.plugins.caldav.CalDavPlugin
import kinuvo.providers.codex.CodexAuth
import kinuvo.server.embedded.sharedProviders
import kinuvo.providers.litert.LiteRtProvider
import kinuvo.server.system
import kinuvo.server.plugin.ServerPlugin
import kinuvo.server.plugin.PrivateFile
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.ProviderKeys
import kinuvo.server.plugin.SecretProvider
import kinuvo.server.Secrets
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.FileAttachments
import kinuvo.plugins.exec.ExecPlugin
import kinuvo.plugins.executor.ExecutorPlugin
import kinuvo.plugins.firecrawl.FirecrawlPlugin
import kinuvo.plugins.mcp.McpPlugin
import kinuvo.plugins.ntfy.NtfyPlugin
import kinuvo.plugins.onepassword.OnePassword
import kinuvo.plugins.protonpass.ProtonPass
import kinuvo.plugins.todoist.TodoistPlugin
import kinuvo.plugins.voice.DownloadedVoice
import java.io.File
import kotlinx.io.files.Path
import kinuvo.server.providers.ModelProviders
import kinuvo.server.providers.plus
import kinuvo.harness.builtin.plugin.ModelSource

/** The model providers a JVM host offers. */
fun jvmProviders(files: AgentFiles, secrets: Secrets = Secrets(), installed: (ServerPlugin) -> Unit = {}): ModelProviders<ModelSource> {
    val attachments = FileAttachments(Path(files.home, "attachments"))::bytes
    val keys = ProviderKeys(files, secrets) { Keychain.system("kinuvo", it) }
    val providers = sharedProviders(files, keys, CodexAuth(browserStore = PrivateFile(Path(files.home, "chatgpt-auth.json"))), attachments) +
        (fmProvider()?.let { ModelProviders.of(it) } ?: ModelProviders()) + ModelProviders.of(LiteRtProvider(File(files.home.toString())))
    return providers.also { it.plugins.forEach(installed) }
}

/** What a JVM host (desktop, the `kinuvo` command) runs with. */
fun jvmPlugins(files: AgentFiles, secrets: Secrets, providers: ModelProviders<ModelSource> = jvmProviders(files, secrets), installed: (ServerPlugin) -> Unit = {}): List<ServerPlugin> =
    providers.plugins.onEach(installed) + listOf(
        { BrowserPlugin(File(files.home.toString(), "browser")) },
        { ExecPlugin(files, secrets) }, { McpPlugin(files, secrets, Keychain.system("kinuvo", "mcp-oauth")) }, { ApplePlugin(loadAppleSystem()) }, { TodoistPlugin() }, { CalDavPlugin() },
        { AnytypePlugin(files, secrets) }, { FirecrawlPlugin() }, { NtfyPlugin() }, { ExecutorPlugin() }, { DownloadedVoice(File(files.home.toString())) },
    ).map { it().also(installed) }

/** The password managers Secret references can point into. */
fun jvmSecretProviders(files: AgentFiles): List<SecretProvider> =
    listOf(
        ProtonPass(File(files.home.toString()), { files.loadEnv(process = emptyMap()) }, Keychain.system("kinuvo", "protonpass")),
        OnePassword(File(files.home.toString()), { files.loadEnv(process = emptyMap()) }, Keychain.system("kinuvo", "1password")),
        Bitwarden(File(files.home.toString()), { files.loadEnv(process = emptyMap()) }, Keychain.system("kinuvo", "bitwarden")),
    )
