package kinuvo.harness.core.skills

import kinuvo.protocol.SessionEntry

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.protocol.Risk
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.requireString
import kotlinx.serialization.json.JsonObject
import kinuvo.harness.core.Agent
import kinuvo.protocol.ConfigScope

/** Renders a Skill for the model, per the agentskills activation format. */
suspend fun SkillRepository.activationText(skill: Skill): String = buildString {
    append("<skill_content name=\"${skill.name}\">\n${body(skill)}\n</skill_content>\n")
    val resources = resources(skill)
    if (resources.isNotEmpty()) {
        append("<skill_resources>\n")
        resources.forEach { append("$it\n") }
        append("</skill_resources>\nRead these with the Skill file tool when needed.\n")
    }
}

/** Looks Skills up when called, so one the Agent created during the run can be activated right away. */
class ActivateSkillTool(private val agent: Agent, private val repo: SkillRepository) : Tool {
    override val risk = Risk.Low

    override val descriptor get() = ToolDescriptor(
        name = ToolNames.ACTIVATE_SKILL,
        description = "Load a Skill's full instructions. Use it when a task matches a Skill's description.",
        requiredParameters = listOf(
            ToolParameterDescriptor("name", "Skill name, as listed under Skills", ToolParameterType.String)
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val name = args.requireString("name")
        val skill = repo.load(agent).firstOrNull { it.name == name } ?: error("No skill named '$name'")
        require(skill.modelInvocable) { "Skill '$name' can only be activated by the user" }
        return repo.activationText(skill)
    }
}

class ReadSkillFileTool(private val agent: Agent, private val repo: SkillRepository, private val maxChars: Int = 50_000) : Tool {
    override val risk = Risk.Low

    override val descriptor = ToolDescriptor(
        name = "read_skill_file",
        description = "Read a file bundled with a Skill (paths as listed in <skill_resources>).",
        requiredParameters = listOf(
            ToolParameterDescriptor("name", "Skill name", ToolParameterType.String),
            ToolParameterDescriptor("path", "Path relative to the skill directory", ToolParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val name = args.requireString("name")
        val skill = repo.load(agent).firstOrNull { it.name == name } ?: error("No skill named '$name'")
        val text = repo.readFile(skill, args.requireString("path"))
        return if (text.length > maxChars) text.take(maxChars) + "\n…[truncated]" else text
    }
}

/** Lets the Agent create, edit or delete Skills in its own skills dir only. */
class SkillManageTool(private val agent: Agent, private val repo: SkillRepository) : Tool {
    override val risk = Risk.High

    override val descriptor = ToolDescriptor(
        name = "skill_manage",
        description = "Create, edit or delete one of your own Skills. content is the full SKILL.md, starting with " +
            "frontmatter (---, name, description, ---) followed by the instructions. Shared Skills are read-only; " +
            "to change one, create your own Skill with the same name. New Skills appear in the catalog on the next turn; the Skill activation tool can load them immediately.",
        requiredParameters = listOf(
            ToolParameterDescriptor("action", "create | edit | delete", ToolParameterType.Enum(arrayOf("create", "edit", "delete"))),
            ToolParameterDescriptor("name", "Skill name (lowercase letters, digits, hyphens)", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("content", "Full SKILL.md for create/edit", ToolParameterType.String),
            ToolParameterDescriptor("reason", "Reason for a background Reflection proposal", ToolParameterType.String),
            ToolParameterDescriptor("evidence", "Source identifiers supporting a background Reflection proposal", ToolParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val name = args.requireString("name")
        val own = repo.exists(agent, ConfigScope.Agent, name)
        when (val action = args.requireString("action")) {
            "create", "edit" -> {
                if (action == "create") require(!own) { "You already have a skill '$name'; use edit" }
                if (action == "edit") require(own) {
                    if (repo.load(agent).any { it.name == name }) "'$name' is a shared skill and read-only; create your own copy instead"
                    else "No skill '$name' of yours to edit"
                }
                repo.save(agent, ConfigScope.Agent, name, args.requireString("content"))
                return "Saved skill '$name'. It will be listed on the next turn; the Skill activation tool can load it now."
            }
            "delete" -> {
                require(own) { "No skill '$name' of yours to delete" }
                check(repo.delete(agent, ConfigScope.Agent, name)) { "No skill '$name' of yours to delete" }
                return "Deleted skill '$name'."
            }
            else -> error("action must be create, edit or delete")
        }
    }
}

/** `/name rest` activates a user-invocable Skill; returns null when [text] isn't a Skill command. */
suspend fun SkillRepository.slashActivation(agent: Agent, text: String): Pair<SessionEntry.Activation, String>? {
    val match = Regex("^/([a-z0-9-]+)(?:\\s+(.*))?$", RegexOption.DOT_MATCHES_ALL).find(text.trim()) ?: return null
    val skill = load(agent).firstOrNull { it.name == match.groupValues[1] && it.userInvocable } ?: return null
    val rest = match.groupValues[2].ifBlank { "Let's use the ${skill.name} skill." }
    return SessionEntry.Activation(skill.name, activationText(skill)) to rest
}
