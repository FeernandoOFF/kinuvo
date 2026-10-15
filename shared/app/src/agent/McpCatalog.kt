package kinuvo.app.agent

import kinuvo.protocol.ConfigScope
import kinuvo.protocol.McpOAuthClient
import kinuvo.protocol.McpServerInfo

/** How a catalog server signs in: OAuth in the browser, a token in a header, or the user's own OAuth app. */
enum class McpPresetAuth { OAuth, Token, OwnClient }

/** A well-known hosted MCP server, added by filling in only what its sign-in needs. */
data class McpPreset(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val auth: McpPresetAuth = McpPresetAuth.OAuth,
    val scopes: List<String> = emptyList(),
    val help: String? = null,
) {
    /** The server to save once the user fills in what [auth] asks for. */
    fun server(scope: ConfigScope = ConfigScope.Agent) = McpServerInfo(id, scope, url = url,
        headers = if (auth == McpPresetAuth.Token) mapOf("Authorization" to "Bearer ") else emptyMap(),
        oauth = if (auth == McpPresetAuth.OwnClient) McpOAuthClient("", scopes = scopes) else null)
}

private const val GOOGLE_HELP = "https://developers.google.com/workspace/guides/configure-mcp-servers"

val McpCatalog = listOf(
    McpPreset("notion", "Notion", "Pages, databases and comments in your workspace.", "https://mcp.notion.com/mcp",
        help = "https://developers.notion.com/guides/mcp/get-started-with-mcp"),
    McpPreset("linear", "Linear", "Issues, projects and cycles.", "https://mcp.linear.app/mcp", help = "https://linear.app/docs/mcp"),
    McpPreset("atlassian", "Jira and Confluence", "Atlassian Cloud issues and pages.", "https://mcp.atlassian.com/v2/mcp",
        help = "https://support.atlassian.com/atlassian-ai-gateway/docs/get-started-with-the-atlassian-remote-mcp-server/"),
    McpPreset("todoist", "Todoist", "Tasks and projects.", "https://ai.todoist.net/mcp"),
    McpPreset("github", "GitHub", "Repositories, issues and pull requests; needs a personal access token.", "https://api.githubcopilot.com/mcp/",
        McpPresetAuth.Token, help = "https://github.com/github/github-mcp-server"),
    McpPreset("google-calendar", "Google Calendar", "Your calendars and events; needs your own Google OAuth client.", "https://calendarmcp.googleapis.com/mcp/v1",
        McpPresetAuth.OwnClient, listOf("https://www.googleapis.com/auth/calendar.calendarlist.readonly",
            "https://www.googleapis.com/auth/calendar.events.freebusy", "https://www.googleapis.com/auth/calendar.events.readonly"), GOOGLE_HELP),
    McpPreset("gmail", "Gmail", "Read mail and draft replies; needs your own Google OAuth client.", "https://gmailmcp.googleapis.com/mcp/v1",
        McpPresetAuth.OwnClient, listOf("https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.compose"), GOOGLE_HELP),
    McpPreset("google-drive", "Google Drive", "Find and read your files; needs your own Google OAuth client.", "https://drivemcp.googleapis.com/mcp/v1",
        McpPresetAuth.OwnClient, listOf("https://www.googleapis.com/auth/drive.readonly", "https://www.googleapis.com/auth/drive.file"), GOOGLE_HELP),
)

private const val PRESET = "preset/"

/** The [kinuvo.app.navigation.Route.Settings.item] that adds [preset]. */
fun presetItem(preset: McpPreset) = PRESET + preset.id

fun presetOf(item: String?) = item?.removePrefix(PRESET)?.takeIf { item.startsWith(PRESET) }?.let { id -> McpCatalog.firstOrNull { it.id == id } }
