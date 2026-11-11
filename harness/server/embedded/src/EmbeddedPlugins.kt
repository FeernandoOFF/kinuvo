package kinuvo.harness.server

import kinuvo.harness.anytype.AnytypePlugin
import kinuvo.harness.caldav.CalDavPlugin
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.plugin.ModelSource
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.system
import kinuvo.harness.executor.ExecutorPlugin
import kinuvo.harness.firecrawl.FirecrawlPlugin
import kinuvo.harness.mcp.McpPlugin
import kinuvo.harness.ntfy.NtfyPlugin
import kinuvo.harness.todoist.TodoistPlugin
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.ProviderKeys

/** The plugins every phone's Server includes besides its platform's model providers. */
internal fun sharedPlugins(files: AgentFiles) = listOf(McpPlugin(files), FirecrawlPlugin(), NtfyPlugin(), TodoistPlugin(), CalDavPlugin(), ExecutorPlugin(), AnytypePlugin(files))

/** The API keys phones' providers keep: in the platform's keychain when the app set one up, else in private files. */
internal fun embeddedKeys(files: AgentFiles) = ProviderKeys(files, Secrets()) { Keychain.system("kinuvo", it) }

/** What a phone's Server runs with: [plugins], including its model providers' admins, and the [modelSources] it registers with the built-in harness. */
class EmbeddedPlugins(val plugins: List<HarnessPlugin>, val modelSources: () -> List<ModelSource>)
