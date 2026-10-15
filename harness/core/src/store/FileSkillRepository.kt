package kinuvo.harness.core.store

import kinuvo.harness.core.Agent
import kinuvo.harness.core.skills.Skill
import kinuvo.harness.core.skills.SkillRepository
import kinuvo.harness.core.skills.safeRelativePath
import kinuvo.protocol.ConfigScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

/** Skills as agentskills folders (`<name>/SKILL.md` plus bundled files). [userHome] expands `~/` in extra skill folders. */
class FileSkillRepository(private val files: AgentFiles, private val userHome: Path?) : SkillRepository {
    private val lock = Mutex()

    override suspend fun all(agent: Agent): List<Skill> = roots(agent).flatMap { (root, scope) ->
        root.children().filter { it.isDirectory() }.mapNotNull { dir ->
            Path(dir, Skill.SKILL_FILE).readTextOrNull()?.let { Skill.parse(it, dir.name, scope, dir.toString()) }
        }
    }

    override suspend fun content(skill: Skill): String = Path(Path(skill.location), Skill.SKILL_FILE).readText()

    override suspend fun resources(skill: Skill, limit: Int): List<String> {
        fun walk(dir: Path, prefix: String): List<String> = dir.children().filterNot { it.name.startsWith(".") }.flatMap {
            if (it.isDirectory()) walk(it, "$prefix${it.name}/") else listOf("$prefix${it.name}")
        }
        return walk(Path(skill.location), "").filter { it != Skill.SKILL_FILE }.sorted().take(limit)
    }

    override suspend fun readFile(skill: Skill, path: String): String {
        val file = Path(skill.location, safeRelativePath(path))
        require(isInside(Path(skill.location), file)) { "Path '$path' must stay inside the skill directory" }
        require(file.isFile()) { "No such file in '${skill.name}'" }
        return file.readText()
    }

    override suspend fun save(agent: Agent, scope: ConfigScope, name: String, content: String, files: Map<String, String>) = lock.withLock {
        saveFiles(agent, scope, name, content, files)
    }

    override suspend fun saveReviewed(agent: Agent, name: String, content: String, expected: String?) = lock.withLock {
        Skill.validate(name, content)
        val dir = Path(files.skills(agent.id), name)
        val current = Path(dir, Skill.SKILL_FILE).readTextOrNull()
        if (current == content.trimEnd() + "\n") return@withLock
        check(current == expected && dir.isDirectory() == (expected != null)) {
            "Skill changed since this draft. Reject it and reflect again."
        }
        saveFiles(agent, ConfigScope.Agent, name, content, emptyMap())
    }

    private fun saveFiles(agent: Agent, scope: ConfigScope, name: String, content: String, files: Map<String, String>) {
        Skill.validate(name, content)
        val dir = Path(writableRoot(agent, scope), name)
        val targets = files.mapKeys { (path, _) ->
            Path(dir, safeRelativePath(path)).also { require(isInside(dir, it)) { "File '$path' must stay inside the skill directory" } }
        }
        targets.forEach { (file, text) -> writeAtomically(file, text) }
        writeAtomically(Path(dir, Skill.SKILL_FILE), content.trimEnd() + "\n")
    }

    override suspend fun exists(agent: Agent, scope: ConfigScope, name: String): Boolean =
        Skill.NAME_PATTERN.matches(name) && Path(writableRoot(agent, scope), name).isDirectory()

    override suspend fun delete(agent: Agent, scope: ConfigScope, name: String): Boolean = lock.withLock {
        require(Skill.NAME_PATTERN.matches(name)) { "Invalid skill name '$name'" }
        val dir = Path(writableRoot(agent, scope), name)
        dir.isDirectory().also { if (it) deleteTree(dir) }
    }

    private fun writableRoot(agent: Agent, scope: ConfigScope) = when (scope) {
        ConfigScope.Agent -> files.skills(agent.id)
        ConfigScope.Shared -> files.sharedSkills
        ConfigScope.External -> error("Skills in extra skill folders are read-only")
    }

    private fun roots(agent: Agent): List<Pair<Path, ConfigScope>> =
        listOf(files.skills(agent.id) to ConfigScope.Agent, files.sharedSkills to ConfigScope.Shared) +
            agent.config.skillDirs.mapNotNull { dir -> expandHome(dir)?.let { it to ConfigScope.External } }

    private fun expandHome(dir: String): Path? = when {
        dir == "~" || dir.startsWith("~/") -> userHome?.let { Path(it, dir.drop(2)) }
        else -> Path(dir)
    }
}
