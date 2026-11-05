package kinuvo.harness.server

import kinuvo.harness.anytype.AnytypePlugin
import kinuvo.harness.caldav.CalDavPlugin
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.executor.ExecutorPlugin
import kinuvo.harness.firecrawl.FirecrawlPlugin
import kinuvo.harness.mcp.McpPlugin
import kinuvo.harness.ntfy.NtfyPlugin
import kinuvo.harness.todoist.TodoistPlugin

/** The plugins every phone's Server includes besides its platform's model providers. */
internal fun sharedPlugins(files: AgentFiles) = listOf(McpPlugin(files), FirecrawlPlugin(), NtfyPlugin(), TodoistPlugin(), CalDavPlugin(), ExecutorPlugin(), AnytypePlugin(files))
