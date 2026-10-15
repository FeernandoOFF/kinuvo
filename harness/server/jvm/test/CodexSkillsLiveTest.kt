package kinuvo.harness.server

import kinuvo.harness.codex.*

import kinuvo.protocol.AgentEvent
import kinuvo.harness.server.Harness
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class CodexSkillsLiveTest {
    @Test
    fun manualSkillChainsIntoModelSkill() = runBlocking {
        if (System.getenv("KINUVO_LIVE") != "1") return@runBlocking
        val home = Files.createTempDirectory("harness-live-skills").toFile()
        fun skill(name: String, text: String) = File(home, "skills/$name").apply { mkdirs() }.resolve("SKILL.md").writeText(text)
        skill("grill-me", "---\nname: grill-me\ndescription: Grill the user about a plan\ndisable-model-invocation: true\n---\n\nCall the Skill tool with \"grilling\".\n")
        skill("grilling", "---\nname: grilling\ndescription: Interview the user relentlessly about a plan\n---\n\nAsk exactly one numbered question per round, starting with 'Q1'.\n")

        val harness = Harness(Path(home.path))
        val agent = harness.store.agents().single().let { it.copy(config = it.config.copy(reasoning = "low", skillDirs = emptyList())) }
        val session = harness.newSession(agent)
        val (activation, message) = harness.slashActivation(agent, "/grill-me I want to build a birdhouse")!!

        val events = runBlocking { harness.runner(agent).run(session.id, message, activation).toList() }
        val calls = events.filterIsInstance<AgentEvent.ToolCallStart>()
        val text = events.filterIsInstance<AgentEvent.TextMessageEnd>().joinToString { it.text }
        assertTrue(calls.any { it.toolCallName == "activate_skill" && "grilling" in it.args }, "calls: $calls")
        assertTrue("Q1" in text, "got: $text")
    }
}
