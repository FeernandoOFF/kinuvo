package kinuvo.harness.core.tools

import kinuvo.protocol.Risk

/** A tool's result-count parameter; [lines] counts lines of text, which the Result limits' text budget bounds instead of maxItems. */
data class ItemLimit(val parameter: String, val max: Int, val lines: Boolean = false)

data class CoreToolEntry(
    val name: String,
    val group: ToolGroup,
    val risk: Risk,
    val kind: ToolKind,
    val repeatable: Boolean,
    val summary: String,
    val hint: String? = null,
    val documentation: String? = null,
    val itemLimit: ItemLimit? = null,
    val paging: Boolean = false,
)

object CoreToolRegistry {
    val entries = listOf(
        CoreToolEntry(ToolNames.ARTIFACT, ToolGroup.Interactive, Risk.Low, ToolKind.Standard, true, "Create or update an Artifact for the user."),
        CoreToolEntry(ToolNames.TASKS, ToolGroup.Data, Risk.Low, ToolKind.Standard, true, "Read and manage Tasks on the Board.", hint = "Tasks live in the user's task provider and appear on the Board. Agents can work assigned Tasks; waiting on the user leaves the provider Task incomplete."),
        CoreToolEntry(ToolNames.SHOW_HTML, ToolGroup.Interactive, Risk.Low, ToolKind.Standard, true, "Show an interactive HTML Surface in the chat."),
        CoreToolEntry(ToolNames.SHOW_UI, ToolGroup.Interactive, Risk.Low, ToolKind.Standard, true, "Show native interactive Surfaces using A2UI v1.0."),
        CoreToolEntry(ToolNames.ASK_USER, ToolGroup.Interactive, Risk.Low, ToolKind.AskUser, true, "Ask the user one or more questions as a form and wait for their answers."),
        CoreToolEntry(ToolNames.WEB_SEARCH, ToolGroup.Core, Risk.Low, ToolKind.Standard, true, "Search the web.", itemLimit = ItemLimit("limit", 20)),
        CoreToolEntry(ToolNames.WEB_FETCH, ToolGroup.Core, Risk.Low, ToolKind.Standard, true, "Fetch a URL over HTTP(S) and return its text content (HTML is reduced to plain text)."),
        CoreToolEntry(ToolNames.GENERATE_IMAGE, ToolGroup.Interactive, Risk.Medium, ToolKind.Standard, true, "Generate or edit images."),
        CoreToolEntry(ToolNames.MEMORY, ToolGroup.Core, Risk.Low, ToolKind.Standard, false, "Persist concise, durable information across Sessions.", hint = "The memory tool edits this Agent's Memory and User Notes. Their current contents appear in the personal context."),
        CoreToolEntry(ToolNames.FIND_TOOLS, ToolGroup.External, Risk.Low, ToolKind.Standard, true, "Search the tool catalog for more tools.", hint = "More tools are in the tool catalog: search it before concluding a tool doesn't exist.", itemLimit = ItemLimit("limit", 25)),
        CoreToolEntry(ToolNames.DESCRIBE_TOOL, ToolGroup.External, Risk.Low, ToolKind.Standard, true, "A catalogued tool's description and parameters (JSON Schema)."),
        CoreToolEntry(ToolNames.CALL_TOOL, ToolGroup.External, Risk.Low, ToolKind.Standard, true, "Run a catalogued tool; it asks for Approval like the tool itself would."),
        CoreToolEntry(ToolNames.ROUTINE_REPORT, ToolGroup.Internal, Risk.Low, ToolKind.Standard, true, "Finish a Routine and report its outcome."),
        CoreToolEntry(ToolNames.SCHEDULE, ToolGroup.Data, Risk.High, ToolKind.Standard, true, "Manage your Routines: prompts you run on a schedule, each in a fresh Session by default.", hint = "Routines schedule future Agent work on the Server; use the scheduling tool instead of claiming you will remember to act later."),
        CoreToolEntry(ToolNames.KNOWLEDGE, ToolGroup.Data, Risk.Low, ToolKind.Standard, true, "The user's knowledge base (their own note system).", hint = "Knowledge is the user's shared note system. Follow its Conventions. Asking for advice or options is not authorization to edit Notes."),
        CoreToolEntry(ToolNames.KNOWLEDGE_REWRITE, ToolGroup.Data, Risk.Medium, ToolKind.Standard, true, "Replace text in a Knowledge Note."),
        CoreToolEntry(ToolNames.REFLECTION_FEEDBACK, ToolGroup.External, Risk.Medium, ToolKind.Standard, true, "Classify this review note before finishing."),
        CoreToolEntry(ToolNames.CONVENTIONS, ToolGroup.External, Risk.Medium, ToolKind.Standard, true, "Propose how the user organizes Knowledge, preserving their other Conventions."),
        CoreToolEntry(ToolNames.ACTIVATE_SKILL, ToolGroup.Skills, Risk.Low, ToolKind.Standard, true, "Load a Skill's full instructions.", hint = "Before specialized work, inspect the Current Skills catalog and activate a matching Skill with the Skill activation tool."),
        CoreToolEntry(ToolNames.READ_SKILL_FILE, ToolGroup.Skills, Risk.Low, ToolKind.Standard, true, "Read a file bundled with a Skill (paths as listed in <skill_resources>).", hint = "Read an activated Skill's referenced resources with the Skill file tool when its instructions call for them."),
        CoreToolEntry(ToolNames.SKILL_MANAGE, ToolGroup.Skills, Risk.High, ToolKind.Standard, true, "Create, edit or delete one of your own Skills.", hint = "When Skill instructions are outdated or contradicted by tool results, identify the concrete correction. Leave automatic learning to background Reflection, which proposes improvements for review; drafts are unavailable until approved. Explicit user requests to edit a Skill use the Skill management tool and the normal Approval policy."),
        CoreToolEntry(ToolNames.SHELL, ToolGroup.Exec, Risk.Medium, ToolKind.Shell, true, "Run a bash command (fresh process each call; cd/export don't persist)."),
        CoreToolEntry("read_file", ToolGroup.Exec, Risk.Low, ToolKind.Standard, true, "Read a text file from the workspace.", itemLimit = ItemLimit("limit", 2000, lines = true), paging = true),
        CoreToolEntry("write_file", ToolGroup.Exec, Risk.Medium, ToolKind.Standard, true, "Create or overwrite a file in the workspace with the given content."),
        CoreToolEntry("edit_file", ToolGroup.Exec, Risk.Medium, ToolKind.Standard, true, "Replace one exact, unique occurrence of old with new in a workspace file."),
        CoreToolEntry("list_files", ToolGroup.Exec, Risk.Low, ToolKind.Standard, true, "List files under a workspace folder (skips .git, build, node_modules), optionally filtered by a glob like **/*.kt."),
        CoreToolEntry("process_output", ToolGroup.Exec, Risk.Low, ToolKind.Standard, true, "Check a background process started with shell(background=true): running state, exit code and the end of its output."),
        CoreToolEntry("process_kill", ToolGroup.Exec, Risk.Low, ToolKind.Standard, true, "Stop a background process started with shell(background=true)."),
        CoreToolEntry(ToolNames.RUN_CODE, ToolGroup.Automation, Risk.Low, ToolKind.Standard, true, "Run a JavaScript Script to chain, loop over or filter many tool calls in one step.", hint = "For many related tool calls (loops, filtering, chaining), write one Script with the Script tool instead of calling them one by one; only its return value comes back."),
        CoreToolEntry(ToolNames.COMPUTER_LIST, ToolGroup.Automation, Risk.Low, ToolKind.Standard, true, "List the Computers you can control (paired phones and computers) and whether each is available."),
        CoreToolEntry("computer_capture", ToolGroup.Automation, Risk.Medium, ToolKind.Standard, true, "See a Computer's screen: its foreground app, numbered elements and a screenshot."),
        CoreToolEntry(ToolNames.COMPUTER_ACT, ToolGroup.Automation, Risk.Medium, ToolKind.ComputerAction, true, "Act on a Computer's screen, then see the result."),
        CoreToolEntry("request_takeover", ToolGroup.Automation, Risk.Low, ToolKind.Standard, true, "Ask the user to take over a Computer."),
        CoreToolEntry("computer_apps", ToolGroup.Automation, Risk.Low, ToolKind.Standard, true, "List installed apps on a Computer."),
        CoreToolEntry("computer_windows", ToolGroup.Automation, Risk.Low, ToolKind.Standard, true, "List Computer windows and their frames."),
        CoreToolEntry(ToolNames.DEVICE_TOOLS, ToolGroup.Data, Risk.Low, ToolKind.Standard, true, "List enabled paired Device tools.", hint = "Device tools depend on paired Devices being available; check tool results for availability."),
        CoreToolEntry("contacts_lookup", ToolGroup.Data, Risk.Low, ToolKind.Standard, true, "Find matching Contacts on a paired Device.", itemLimit = ItemLimit("limit", 20)),
        CoreToolEntry("workouts_list", ToolGroup.Data, Risk.Low, ToolKind.Standard, true, "Read Health workouts from a paired Device.", itemLimit = ItemLimit("limit", 20)),
        CoreToolEntry("sleep_sessions", ToolGroup.Data, Risk.Low, ToolKind.Standard, true, "Read Health sleep sessions from a paired Device."),
        CoreToolEntry("browser_tabs", ToolGroup.Automation, Risk.Low, ToolKind.Standard, true, "List this Agent's shared Browser tabs."),
        CoreToolEntry("browser_open", ToolGroup.Automation, Risk.Low, ToolKind.Standard, false, "Open a Browser tab."),
        CoreToolEntry("browser_capture", ToolGroup.Automation, Risk.Low, ToolKind.Standard, true, "Capture a Browser tab's elements and screenshot."),
        CoreToolEntry("browser_navigate", ToolGroup.Automation, Risk.Low, ToolKind.Standard, false, "Navigate a Browser tab to a URL."),
        CoreToolEntry("browser_back", ToolGroup.Automation, Risk.Low, ToolKind.Standard, true, "Go back in a Browser tab's history."),
        CoreToolEntry("browser_forward", ToolGroup.Automation, Risk.Low, ToolKind.Standard, true, "Go forward in a Browser tab's history."),
        CoreToolEntry("browser_click", ToolGroup.Automation, Risk.High, ToolKind.Standard, false, "Click a captured Browser element."),
        CoreToolEntry("browser_type", ToolGroup.Automation, Risk.High, ToolKind.Standard, false, "Fill a captured Browser element with text."),
        CoreToolEntry("browser_scroll", ToolGroup.Automation, Risk.Low, ToolKind.Standard, true, "Scroll a Browser tab."),
        CoreToolEntry("browser_wait", ToolGroup.Automation, Risk.Low, ToolKind.Standard, true, "Wait and capture a Browser tab."),
        CoreToolEntry("browser_close", ToolGroup.Automation, Risk.Medium, ToolKind.Standard, false, "Close a Browser tab."),
    )
    private val byName = entries.associateBy { it.name }.also { require(it.size == entries.size) }
    operator fun get(name: String): CoreToolEntry? = byName[name]
}

interface CoreTool : Tool {
    override val coreMetadata get() = metadata
    val metadata: CoreToolEntry get() = requireNotNull(CoreToolRegistry[descriptor.name]) { "Unregistered core tool: ${descriptor.name}" }
    override val group get() = metadata.group
    override val risk get() = metadata.risk
    override val kind get() = metadata.kind
    override val repeatable get() = metadata.repeatable
}
