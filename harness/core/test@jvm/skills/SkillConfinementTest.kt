package kinuvo.harness.core.skills

import kinuvo.harness.core.TestHome
import kinuvo.harness.core.store.writeAtomically
import kinuvo.protocol.ConfigScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SkillConfinementTest {
    @Test
    fun symlinksCantLeadOutOfASkill() = runTest {
        val home = TestHome()
        val outside = Files.createTempDirectory("outside").also { Files.writeString(it.resolve("secret"), "s") }
        val dir = Path(home.files.skills(home.agent.id), "notes")
        writeAtomically(Path(dir, "SKILL.md"), "---\nname: notes\ndescription: x\n---\n")
        Files.createSymbolicLink(Paths.get(Path(dir, "refs").toString()), outside)
        val skill = home.skills.load(home.agent).single { it.name == "notes" }

        assertFailsWith<IllegalArgumentException> { home.skills.readFile(skill, "refs/secret") }
        assertFailsWith<IllegalArgumentException> {
            home.skills.save(home.agent, ConfigScope.Agent, "notes", "---\nname: notes\ndescription: x\n---\n", mapOf("refs/new" to "x"))
        }
        assertFalse(Files.exists(outside.resolve("new")))
    }
}
