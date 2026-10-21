package kinuvo.harness.core.knowledge

import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.Agent
import kinuvo.harness.core.skills.Skill
import kinuvo.harness.core.skills.SkillRepository
import kinuvo.harness.core.skills.load
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.requireString
import kinuvo.protocol.ConfigScope
import kinuvo.protocol.ReflectionProposal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

/** Reflection can read freely, but its Knowledge and Skill writes only produce drafts. */
class ReflectionChanges(private val agent: Agent, private val knowledge: Knowledge, private val skills: SkillRepository?, private val sources: Map<String, String>, private val review: ReflectionRevision? = null) {
    val proposals = mutableListOf<ReflectionProposal>()

    fun staging(tool: Tool): Tool = object : Tool {
        override val risk = tool.risk
        override val descriptor = tool.descriptor.copy(
            description = tool.descriptor.description + " Writes only stage a proposal for the user's Approval; nothing is applied or activated. Include reason and evidence on writes.",
            optionalParameters = tool.descriptor.optionalParameters.filterNot { it.name in listOf("reason", "evidence") } + listOf(
                ToolParameterDescriptor("reason", "Why this durable change is worth keeping", ToolParameterType.String),
                ToolParameterDescriptor("evidence", "Source identifiers and what they establish; use user-N or tool call IDs from the transcript", ToolParameterType.String),
            ),
        )

        override suspend fun execute(args: JsonObject): String {
            val action = args.requireString("action")
            // Reflection shares the chat's tool list for caching, but has nowhere to show files.
            require(action != "file") { "Reflection can't view files; work from the transcript" }
            if (tool.descriptor.name == ToolNames.KNOWLEDGE && action !in listOf("create", "update")) return tool.execute(args)
            require(action in listOf("create", "update", "edit")) { "Reflection can only propose creating or updating Knowledge and Skills" }
            if (review != null) {
                if (tool.descriptor.name == ToolNames.KNOWLEDGE) require(review.approve && review.proposal.tool == ToolNames.KNOWLEDGE) { "Only revise the reviewed proposal" }
                if (tool.descriptor.name == "skill_manage") require(args.requireString("name") == review.proposal.args.requireString("name")) { "Only change the reviewed Skill" }
            }
            val reason = args.requireString("reason")
            val references = args.requireString("evidence")
            val cited = sources.filterKeys { id -> Regex("(?<![A-Za-z0-9_-])${Regex.escape(id)}(?![A-Za-z0-9_-])").containsMatchIn(references) }
            require(cited.isNotEmpty()) { "Evidence must cite a User message or actual tool result, not an Assistant suggestion" }
            val evidence = cited.values.joinToString("\n\n")
            val clean = JsonObject(args.filterKeys { it !in listOf("reason", "evidence") })
            val proposal = when (tool.descriptor.name) {
                ToolNames.KNOWLEDGE -> {
                    val scope = knowledge.scope()
                    val note = if (action == "update") knowledge.find(args.requireString("ref")) ?: error("No Note to update") else {
                        args.requireString("type")
                        val title = args.requireString("title")
                        require(knowledge.find(title) == null) { "A Note named '$title' already exists; propose updating it" }
                        null
                    }
                    val pinned = if (note != null) JsonObject(clean + ("ref" to JsonPrimitive(note.ref))) else clean
                    ReflectionProposal(Uuid.random().toString(), tool.descriptor.name, pinned, reason, evidence, note = note, scope = scope)
                }
                "conventions" -> {
                    args.requireString("content")
                    ReflectionProposal(Uuid.random().toString(), "conventions", clean, reason, evidence,
                        scope = knowledge.provider(), conventionsBefore = knowledge.conventions())
                }
                "skill_manage" -> {
                    val repo = requireNotNull(skills)
                    val name = args.requireString("name")
                    val content = args.requireString("content")
                    Skill.validate(name, content)
                    val own = repo.load(agent).firstOrNull { it.name == name && it.scope == ConfigScope.Agent }
                    if (action == "create") require(!repo.exists(agent, ConfigScope.Agent, name)) { "Skill already exists; propose edit" }
                    if (action == "edit") require(own != null) { "Only own Skills can be edited" }
                    ReflectionProposal(Uuid.random().toString(), tool.descriptor.name, clean, reason, evidence, skillBefore = own?.let { repo.content(it) })
                }
                else -> error("Unsupported Reflection destination")
            }
            if (proposals.none { it.tool == proposal.tool && it.args == proposal.args }) proposals += proposal
            return "Proposed for Approval. Not saved or activated."
        }
    }
}
