package kinuvo.harness.core.skills

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import kinuvo.harness.core.Agent
import kinuvo.protocol.ConfigScope

/**
 * A Skill. [modelInvocable]/[userInvocable] follow Claude Code's frontmatter flags; [location] is where the
 * [SkillRepository] found it, opaque to everything else.
 */
data class Skill(
    val name: String,
    val description: String,
    val scope: ConfigScope,
    val location: String,
    val modelInvocable: Boolean = true,
    val userInvocable: Boolean = true,
) {
    val editable get() = scope == ConfigScope.Agent

    companion object {
        const val SKILL_FILE = "SKILL.md"
        val NAME_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

        /** A Skill from its SKILL.md, or null without a description; [fallbackName] is used when the frontmatter has none. */
        fun parse(content: String, fallbackName: String, scope: ConfigScope, location: String): Skill? {
            val fields = frontmatter(content)
            return Skill(
                name = fields["name"]?.takeIf { it.isNotBlank() } ?: fallbackName,
                description = fields["description"]?.takeIf { it.isNotBlank() } ?: return null,
                scope = scope,
                location = location,
                modelInvocable = fields["disable-model-invocation"]?.toBooleanStrictOrNull() != true,
                userInvocable = fields["user-invocable"]?.toBooleanStrictOrNull() != false,
            )
        }

        /** Checks a Skill about to be saved as [name]. */
        fun validate(name: String, content: String) {
            require(NAME_PATTERN.matches(name) && name.length <= 64) { "Invalid skill name '$name'" }
            val fields = frontmatter(content)
            require(fields["name"] == name) { "Frontmatter name must be '$name'" }
            require(!fields["description"].isNullOrBlank()) { "Frontmatter needs a description" }
        }

        /** Top-level scalar frontmatter fields; falls back to `key: value` lines when YAML is malformed. */
        internal fun frontmatter(text: String): Map<String, String> {
            val raw = splitFrontmatter(text).first ?: return emptyMap()
            return runCatching {
                (Yaml.default.parseToYamlNode(raw) as YamlMap).entries.mapNotNull { (k, v) ->
                    (v as? YamlScalar)?.let { k.content to it.content }
                }.toMap()
            }.getOrElse {
                raw.lineSequence().mapNotNull { line ->
                    Regex("^([A-Za-z][\\w-]*):\\s*(.*)$").find(line)?.destructured?.let { (k, v) -> k to v.trim().trim('"', '\'') }
                }.toMap()
            }
        }
    }
}

/**
 * Where an Agent's Skills come from, in precedence order: its own, the shared ones, then its extra skill folders.
 * Only the Agent's own and the shared Skills can be changed.
 */
interface SkillRepository {
    /** Every Skill in precedence order, including ones a Skill of the same name shadows. */
    suspend fun all(agent: Agent): List<Skill>
    /** SKILL.md as written. */
    suspend fun content(skill: Skill): String
    /** Bundled files relative to the Skill, excluding SKILL.md. */
    suspend fun resources(skill: Skill, limit: Int = 50): List<String>
    /** A bundled file by relative path; it must stay inside the Skill. */
    suspend fun readFile(skill: Skill, path: String): String
    /** Saves SKILL.md ([content]) plus [files] by relative path; see [Skill.validate]. */
    suspend fun save(agent: Agent, scope: ConfigScope, name: String, content: String, files: Map<String, String> = emptyMap())
    suspend fun saveReviewed(agent: Agent, name: String, content: String, expected: String?) {
        val own = all(agent).firstOrNull { it.scope == ConfigScope.Agent && it.name == name }
        check(own?.let { content(it) } == expected && exists(agent, ConfigScope.Agent, name) == (expected != null)) {
            "Skill changed since this draft. Reject it and reflect again."
        }
        save(agent, ConfigScope.Agent, name, content)
    }
    /** Whether the Agent's own or the shared Skills hold one stored as [name], even one that doesn't load. */
    suspend fun exists(agent: Agent, scope: ConfigScope, name: String): Boolean
    suspend fun delete(agent: Agent, scope: ConfigScope, name: String): Boolean
}

/** The Skills in effect: the first found with a given name wins. */
suspend fun SkillRepository.load(agent: Agent): List<Skill> = all(agent).distinctBy { it.name }

/** SKILL.md without its frontmatter. */
suspend fun SkillRepository.body(skill: Skill): String = splitFrontmatter(content(skill)).second.trim()

/** A path relative to a Skill that can't leave it: no absolute paths, no `..`. */
fun safeRelativePath(path: String): String {
    val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    require(!path.startsWith("/") && !path.startsWith("\\") && ':' !in path && parts.isNotEmpty() && parts.none { it == ".." }) {
        "Path '$path' must stay inside the skill directory"
    }
    return parts.joinToString("/")
}

internal fun splitFrontmatter(text: String): Pair<String?, String> {
    val normalized = text.replace("\r\n", "\n")
    if (!normalized.startsWith("---\n")) return null to normalized
    val end = normalized.indexOf("\n---", 4)
    if (end < 0) return null to normalized
    val after = normalized.indexOf('\n', end + 4).let { if (it < 0) normalized.length else it + 1 }
    return normalized.substring(4, end) to normalized.substring(after)
}
