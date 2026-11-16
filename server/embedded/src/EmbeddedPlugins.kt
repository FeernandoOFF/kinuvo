package kinuvo.server.embedded

import kinuvo.plugins.anytype.AnytypePlugin
import kinuvo.plugins.caldav.CalDavPlugin
import kinuvo.server.Secrets
import kinuvo.harness.builtin.plugin.ModelSource
import kinuvo.server.store.AgentFiles
import kinuvo.server.system
import kinuvo.plugins.executor.ExecutorPlugin
import kinuvo.plugins.firecrawl.FirecrawlPlugin
import kinuvo.plugins.mcp.McpPlugin
import kinuvo.plugins.ntfy.NtfyPlugin
import kinuvo.plugins.todoist.TodoistPlugin
import kinuvo.server.plugin.ServerPlugin
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.ProviderKeys
import kinuvo.server.providers.ModelProviders
import kinuvo.server.providers.plus
import kinuvo.providers.codex.CodexAuth
import kinuvo.providers.codex.CodexProvider
import kinuvo.providers.codex.OpenAIProvider
import kinuvo.providers.koog.compatibleProviders
import kinuvo.protocol.session.Attachment

/** The plugins every phone's Server includes besides its platform's model providers. */
internal fun sharedPlugins(files: AgentFiles, mcpKeychain: Keychain? = Keychain.system("kinuvo", "mcp-oauth")) = listOf(McpPlugin(files, keychain = mcpKeychain), FirecrawlPlugin(), NtfyPlugin(), TodoistPlugin(), CalDavPlugin(), ExecutorPlugin(), AnytypePlugin(files))

/** Codex, OpenAI and the custom endpoints: the providers every host offers, which read attachments through [attachments]. */
fun sharedProviders(files: AgentFiles, keys: ProviderKeys, codexAuth: CodexAuth, attachments: (Attachment) -> ByteArray): ModelProviders<ModelSource> {
    val endpoints = compatibleProviders(keys, files, attachments)
    return ModelProviders.of(CodexProvider(codexAuth, attachments)) + ModelProviders.of(OpenAIProvider(keys, attachments)) + ModelProviders(listOf(endpoints)) { endpoints.modelSources }
}

/** The API keys phones' providers keep: in the platform's keychain when the app set one up, else in private files. */
internal fun embeddedKeys(files: AgentFiles) = ProviderKeys(files, Secrets()) { Keychain.system("kinuvo", it) }

/** What a phone's Server runs with: [plugins], including its model providers' admins, and the [modelSources] it registers with the built-in harness. */
class EmbeddedPlugins(val plugins: List<ServerPlugin>, val modelSources: () -> List<ModelSource>)
