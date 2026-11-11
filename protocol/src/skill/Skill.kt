package kinuvo.protocol.skill

import kinuvo.protocol.agent.ConfigScope
import kotlinx.serialization.Serializable

/** A Skill an Agent can use; [userInvocable] ones can be activated with `/name`. [active] is false when a Skill of the same name shadows it. */
@Serializable
data class SkillInfo(
    val name: String,
    val description: String,
    val userInvocable: Boolean = true,
    val modelInvocable: Boolean = true,
    val scope: ConfigScope = ConfigScope.Agent,
    val active: Boolean = true,
)

/** A Skill's SKILL.md and the other files bundled with it. */
@Serializable
data class SkillDetails(val info: SkillInfo, val content: String, val files: List<String> = emptyList())

/** A null [scope] means the Skill the Agent uses under that name. */
@Serializable
data class SkillParams(val agentId: String, val name: String, val scope: ConfigScope? = null)

/** Writes SKILL.md ([content]) plus extra text [files] by relative path; [content]'s frontmatter must name the Skill [name]. */
@Serializable
data class SaveSkillParams(
    val agentId: String,
    val name: String,
    val content: String,
    val scope: ConfigScope = ConfigScope.Agent,
    val files: Map<String, String> = emptyMap(),
)
