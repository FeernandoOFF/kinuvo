package kinuvo.protocol.tool

import kinuvo.protocol.routine.Routine

object ToolNames {
    const val AGENT_MANAGE = "agent_manage"
    const val AGENT_GET = "agent_get"
    const val AGENT_UPDATE = "agent_update"
    const val AGENT_CREATE = "agent_create"
    const val MCP_LIST = "mcp_list"
    const val MCP_ADD = "mcp_add"
    const val MCP_REMOVE = "mcp_remove"
    const val MCP_TEST = "mcp_test"
    const val MCP_LOGIN = "mcp_login"
    const val SESSION_CONFIGURE = "session_configure"
    const val TEAM_HANDOFF = "team_handoff"
    const val GENERATE_IMAGE = "generate_image"
    const val AGENTS_SPAWN = "agents_spawn"
    const val AGENTS_WAIT = "agents_wait"
    const val AGENTS_SEND = "agents_send"
    const val AGENTS_LIST = "agents_list"
    const val MEMORY = "memory"
    const val WEB_FETCH = "web_fetch"
    /** Comes with [WEB_FETCH] when web search is set up. */
    const val WEB_SEARCH = "web_search"
    const val SKILLS = "skills"
    const val ACTIVATE_SKILL = "activate_skill"
    const val READ_SKILL_FILE = "read_skill_file"
    const val SKILL_MANAGE = "skill_manage"
    const val CONVENTIONS = "conventions"
    const val REFLECTION_FEEDBACK = "reflection_feedback"
    const val COMPUTER_LIST = "computer_list"
    const val SHELL = "shell"
    const val SCHEDULE = "schedule"
    const val KNOWLEDGE = "knowledge"
    const val KNOWLEDGE_REWRITE = "knowledge_rewrite"
    const val TASKS = "tasks"
    const val REQUEST_SECRET = "request_secret"
    const val ASK_USER = "ask_user"
    /** Offered when a Device has said which A2UI catalogs it renders. */
    const val SHOW_UI = "show_ui"
    const val SHOW_HTML = "show_html"
    /** Only in Routine runs. */
    const val ROUTINE_REPORT = "routine_report"
    const val ARTIFACT = "artifact"
    const val DEVICE_TOOLS = "device_tools"
    const val RUN_CODE = "run_code"
    const val COMPUTER = "computer"
    /** Its Trust rules are scoped to the app acted in. */
    const val COMPUTER_ACT = "computer_act"
    const val FIND_TOOLS = "find_tools"
    const val DESCRIBE_TOOL = "describe_tool"
    const val CALL_TOOL = "call_tool"

    /** The built-in tools an Agent's `tools:` can turn on. */
    val OPTIONAL = listOf(AGENT_MANAGE, MEMORY, WEB_FETCH, SKILLS, SCHEDULE, KNOWLEDGE, TASKS, ASK_USER, ARTIFACT, DEVICE_TOOLS, RUN_CODE, COMPUTER, "browser")
    val DEFAULT = OPTIONAL
    /** Groups a plugin provides, offered only where one is installed. */
    val FROM_PLUGINS = setOf(RUN_CODE, "browser")
}

/** What a tool is for, so a model's profile and a run's tool scope can allow it by kind. */
enum class ToolGroup {
    /** Memory, fetching and searching the web. */
    Core,
    /** Knowledge, Tasks and Routines. */
    Data,
    /** Talking back to the user: questions, Artifacts, rendered UI, images. */
    Interactive,
    Skills,
    /** The shell and file tools. */
    Exec,
    /** Scripts, browsers, computers and devices. */
    Automation,
    /** MCP and other plugins' tools. */
    External,
    /** Run bookkeeping the harness itself relies on; always allowed. */
    Internal,
}
