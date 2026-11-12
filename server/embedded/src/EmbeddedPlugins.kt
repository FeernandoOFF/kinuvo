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
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.ProviderKeys

/** The plugins every phone's Server includes besides its platform's model providers. */
internal fun sharedPlugins(files: AgentFiles) = listOf(McpPlugin(files), FirecrawlPlugin(), NtfyPlugin(), TodoistPlugin(), CalDavPlugin(), ExecutorPlugin(), AnytypePlugin(files))

/** The API keys phones' providers keep: in the platform's keychain when the app set one up, else in private files. */
internal fun embeddedKeys(files: AgentFiles) = ProviderKeys(files, Secrets()) { Keychain.system("kinuvo", it) }

/** What a phone's Server runs with: [plugins], including its model providers' admins, and the [modelSources] it registers with the built-in harness. */
class EmbeddedPlugins(val plugins: List<HarnessPlugin>, val modelSources: () -> List<ModelSource>)
