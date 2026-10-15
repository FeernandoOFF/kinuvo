package kinuvo.harness.core.skills

import kinuvo.protocol.SessionEntry
import kinuvo.harness.core.TestHome
import kinuvo.harness.core.activations
import kinuvo.harness.core.store.exists
import kinuvo.harness.core.store.writeAtomically
import kinuvo.harness.core.tools.Tool
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillsTest {
    private val home = TestHome()
    private val repo = home.skills
    private val external = Path(home.files.home, "external")
    private val agent = home.agent.let { it.copy(config = it.config.copy(skillDirs = listOf(external.toString()))) }

    private fun skill(root: Path, name: String, frontmatter: String, body: String = "Do $name things.", vararg files: Pair<String, String>) {
        writeAtomically(Path(root, name, "SKILL.md"), "---\n$frontmatter\n---\n\n$body\n")
        files.forEach { (path, text) -> writeAtomically(Path(Path(root, name), path), text) }
    }

    private val own get() = home.files.skills(agent.id)
    private val shared get() = home.files.sharedSkills
    private suspend fun load() = repo.load(agent)
    private suspend fun Tool.run(vararg pairs: Pair<String, String>) = execute(buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })

    @Test
    fun precedenceFlagsAndLenientParsing() = runTest {
        skill(own, "tdd", "name: tdd\ndescription: mine")
        skill(shared, "tdd", "name: tdd\ndescription: shared")
        skill(shared, "grill-me", "name: grill-me\ndescription: Grill me\ndisable-model-invocation: true")
        skill(external, "hidden", "name: hidden\ndescription: model only\nuser-invocable: false")
        skill(external, "broken", "name: broken\ndescription: has: colons: everywhere")
        skill(external, "nodesc", "name: nodesc")

        val skills = load().associateBy { it.name }
        assertEquals(setOf("tdd", "grill-me", "hidden", "broken"), skills.keys)
        assertEquals("mine", skills.getValue("tdd").description)
        assertTrue(skills.getValue("tdd").editable)
        assertFalse(skills.getValue("grill-me").modelInvocable)
        assertFalse(skills.getValue("hidden").userInvocable)
        assertEquals("has: colons: everywhere", skills.getValue("broken").description)
    }

    @Test
    fun catalogHidesManualOnlySkills() = runTest {
        skill(shared, "grill-me", "name: grill-me\ndescription: Grill me\ndisable-model-invocation: true")
        skill(shared, "grilling", "name: grilling\ndescription: Interview the user")
        val text = kinuvo.harness.core.skillCatalogFor(load())
        assertTrue("- grilling: Interview the user" in text)
        assertFalse("grill-me" in text)
    }

    @Test
    fun activateAndReadFiles() = runTest {
        skill(shared, "tdd", "name: tdd\ndescription: tests", "Red, green.", "tests.md" to "write tests", "refs/mocking.md" to "mock less")
        skill(shared, "grill-me", "name: grill-me\ndescription: x\ndisable-model-invocation: true")
        val activate = ActivateSkillTool(agent, repo)
        val read = ReadSkillFileTool(agent, repo)

        val text = activate.run("name" to "tdd")
        assertTrue("<skill_content name=\"tdd\">\nRed, green.\n</skill_content>" in text)
        assertTrue("refs/mocking.md" in text && "tests.md" in text)
        assertFailsWith<IllegalArgumentException> { activate.run("name" to "grill-me") }

        assertEquals("mock less", read.run("name" to "tdd", "path" to "refs/mocking.md"))
        assertFailsWith<IllegalArgumentException> { read.run("name" to "tdd", "path" to "../grill-me/SKILL.md") }
    }

    @Test
    fun agentManagesOnlyItsOwnSkills() = runTest {
        skill(shared, "shared-one", "name: shared-one\ndescription: shared")
        val manage = SkillManageTool(agent, repo)
        val content = "---\nname: notes\ndescription: Take notes\n---\n\nWrite notes."

        manage.run("action" to "create", "name" to "notes", "content" to content)
        assertEquals("Write notes.", repo.body(load().single { it.name == "notes" }))
        assertTrue("Write notes." in ActivateSkillTool(agent, repo).run("name" to "notes"))
        manage.run("action" to "edit", "name" to "notes", "content" to content.replace("Write notes.", "Write better notes."))
        assertEquals("Write better notes.", repo.body(load().single { it.name == "notes" }))

        val e = assertFailsWith<IllegalArgumentException> {
            manage.run("action" to "edit", "name" to "shared-one", "content" to "---\nname: shared-one\ndescription: x\n---\n")
        }
        assertTrue("read-only" in e.message.orEmpty())
        assertFailsWith<IllegalArgumentException> {
            manage.run("action" to "create", "name" to "bad", "content" to "---\nname: other\ndescription: x\n---\n")
        }
        manage.run("action" to "delete", "name" to "notes")
        writeAtomically(Path(own, "broken", "SKILL.md"), "no frontmatter")
        manage.run("action" to "delete", "name" to "broken")
        assertFalse(Path(own, "broken").exists())
        assertTrue(load().none { it.name == "notes" })
        assertFalse(Path(own, "notes").exists())
    }

    @Test
    fun slashActivationRespectsUserInvocable() = runTest {
        skill(shared, "grill-me", "name: grill-me\ndescription: x\ndisable-model-invocation: true", "Grill hard.")
        skill(shared, "hidden", "name: hidden\ndescription: x\nuser-invocable: false")

        val (activation, rest) = repo.slashActivation(agent, "/grill-me my plan")!!
        assertEquals("grill-me", activation.name)
        assertTrue("Grill hard." in activation.content)
        assertEquals("my plan", rest)
        assertNull(repo.slashActivation(agent, "/hidden"))
        assertNull(repo.slashActivation(agent, "just text"))
    }

    @Test
    fun activationsCollectsToolAndUserActivations() {
        val history = listOf(
            SessionEntry.Activation("grill-me", "grill text"),
            SessionEntry.UserMessage("go"),
            SessionEntry.ToolCall("c1", "activate_skill", """{"name":"grilling"}""", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "activate_skill")),
            SessionEntry.ToolResult("c1", "activate_skill", "grilling text"),
            SessionEntry.ToolCall("c2", "activate_skill", """{"name":"nope"}""", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "activate_skill")),
            SessionEntry.ToolResult("c2", "activate_skill", "Error", isError = true),
        )
        assertEquals(
            listOf(SessionEntry.Activation("grill-me", "grill text"), SessionEntry.Activation("grilling", "grilling text")),
            history.activations(),
        )
    }
}
