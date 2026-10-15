package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kinuvo.protocol.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal class SkillCommands : Group(
    "skills", "Manage Skills: the Agent's own (default) or shared by every Agent (--shared).",
    ListSkills(), ShowSkill(), CreateSkill(), InstallSkill(), RemoveSkill(),
) {
    override fun aliases() = mapOf("uninstall" to listOf("remove"), "ls" to listOf("list"))
}

private class ListSkills : ServerCommand("list", "List the Skills the Agent can use.") {
    override suspend fun run() {
        val skills = session.call { client.call(Api.Skills, AgentRef(agentId())) }
        echo(table(listOf("NAME", "SCOPE", "INVOKED BY", "DESCRIPTION"), skills.map { s ->
            val by = listOfNotNull("agent".takeIf { s.modelInvocable }, "/${s.name}".takeIf { s.userInvocable }).joinToString(", ")
            listOf(s.name, s.scope.name.lowercase() + if (s.active) "" else " (shadowed)", by, s.description.take(70))
        }))
    }
}

private class ShowSkill : ServerCommand("show", "Print a Skill's SKILL.md and list its files.") {
    private val name by argument("NAME")
    private val shared by option("--shared", help = "The shared Skill rather than the one the Agent uses").flag()

    override suspend fun run() {
        val s = session.call { client.call(Api.Skill, SkillParams(agentId(), name, ConfigScope.Shared.takeIf { shared })) }
        echo(s.content.trimEnd())
        if (s.files.isNotEmpty()) echo("\nFiles:\n" + s.files.joinToString("\n") { "  $it" })
    }
}

private class CreateSkill : ServerCommand("create", "Write a new Skill from a description and instructions.") {
    private val name by argument("NAME", help = "Lowercase letters, digits and hyphens")
    private val description by option("--description", help = "When the Skill applies").required()
    private val body by option("--body", help = "The instructions; - reads stdin").required()
    private val shared by option("--shared", help = "Share it with every Agent").flag()

    override suspend fun run() {
        val content = "---\nname: $name\ndescription: $description\n---\n\n${textOrStdin(body).trim()}\n"
        session.call { client.call(Api.SaveSkill, SaveSkillParams(agentId(), name, content, scope(shared))) }
        echo("Saved skill '$name'.")
    }
}

private class InstallSkill : ServerCommand("install", "Copy a Skill from a folder with a SKILL.md (or the SKILL.md itself), with its text files.") {
    private val path by argument("PATH")
    private val shared by option("--shared", help = "Share it with every Agent").flag()

    override suspend fun run() {
        val given = Path(path)
        val dir = if (SystemFileSystem.metadataOrNull(given)?.isDirectory == true) given else given.parent ?: Path(".")
        val content = runCatching { readText(Path(dir, "SKILL.md").toString()) }.getOrElse { throw CliktError("No SKILL.md in $dir") }
        val name = Regex("""(?m)^name:\s*["']?([^"'\n]+?)["']?\s*$""").find(content.substringBefore("\n---", ""))?.groupValues?.get(1)
            ?: throw CliktError("SKILL.md has no name in its frontmatter")
        val files = textFiles(dir).filterKeys { it != "SKILL.md" }
        session.call { client.call(Api.SaveSkill, SaveSkillParams(agentId(), name, content, scope(shared), files)) }
        echo("Installed skill '$name'" + if (files.isEmpty()) "." else " with ${files.size} file(s).")
    }

    /** Text files under [root] by relative path, skipping hidden ones and binaries. */
    private fun textFiles(root: Path, prefix: String = ""): Map<String, String> = SystemFileSystem.list(root)
        .filterNot { it.name.startsWith(".") }
        .flatMap { p ->
            val rel = prefix + p.name
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) textFiles(p, "$rel/").toList()
            else listOfNotNull(readText(p.toString()).takeIf { '\u0000' !in it }?.let { rel to it })
        }.toMap()
}

private class RemoveSkill : ServerCommand("remove", "Delete a Skill (Hermes: uninstall).") {
    private val name by argument("NAME")
    private val shared by option("--shared", help = "Delete the shared one").flag()

    override suspend fun run() {
        session.call { client.call(Api.DeleteSkill, SkillParams(agentId(), name, ConfigScope.Shared.takeIf { shared })) }
        echo("Removed skill '$name'.")
    }
}

internal fun scope(shared: Boolean) = if (shared) ConfigScope.Shared else ConfigScope.Agent
