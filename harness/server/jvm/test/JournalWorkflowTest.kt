package kinuvo.harness.server

import kinuvo.harness.testing.*
import ai.koog.prompt.message.Message

import kinuvo.harness.core.modelAlias
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.knowledge.*
import kinuvo.harness.core.routines.RoutineRun
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class JournalWorkflowTest {
    @Test
    fun routineAsksQuestionsThenReadsWorkoutsBeforeWritingTheJournal() = runBlocking {
        val home = Files.createTempDirectory("journal-workflow").toFile()
        val model = scriptedModel { prompt, tools ->
            val history = prompt.messages
            val instructions = history.filterIsInstance<Message.System>().last().textContent()
            assertTrue("Preserve all writing outside it" in instructions)
            assertTrue("<!-- kinuvo-journal:start -->" in instructions)
            assertTrue(tools.any { it.name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "ask_user").modelAlias() })
            val results = history.toolResults
            val call = when (results.size) {
                0 -> StreamFrame.ToolCallComplete("ask", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "ask_user").modelAlias(), """{"questions":[{"id":"reflection","label":"How was today?","kind":"text"}]}""")
                1 -> {
                    check(results.last().output.contains("Rested"))
                    check(tools.any { it.name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.devices"), "workouts_list").modelAlias() })
                    StreamFrame.ToolCallComplete("workouts", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.devices"), "workouts_list").modelAlias(), """{"start_date":"2026-10-05","end_date":"2026-10-05"}""")
                }
                2 -> {
                    check(results.last().output.contains("Running"))
                    StreamFrame.ToolCallComplete("journal", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias(), buildJsonObject {
                        put("action", "create"); put("type", "Journal"); put("title", "2026-10-05")
                        put("append", "## Kinuvo\n<!-- kinuvo-journal:start -->\nRested. Running, 35 min.\n<!-- kinuvo-journal:end -->")
                    }.toString())
                }
                else -> null
            }
            if (call == null) flowOf(StreamFrame.TextDelta("Journal updated"), StreamFrame.End()) else flowOf(call, StreamFrame.End())
        }
        val harness = Harness(home = Path(home.path), modelFactory = { model })
        try {
            withTimeout(5_000) {
                val agent = harness.store.agents().single()
                val routine = Routine("journal", agent.id, "Journal", "Ask, read workouts, then write", Trigger.Every(1440), "Europe/Dublin")
                val session = harness.newSession(agent, RoutineRef(routine.id, routine.name))
                harness.devices.paired = { listOf(ToolDevice("phone", "iPhone", setOf(DeviceToolKind.Workouts))) }
                harness.devices.connect("phone", setOf(DeviceToolKind.Workouts), true) { request ->
                    assertEquals("2026-10-04T23:00:00Z", request.start)
                    assertEquals("2026-10-05T23:00:00Z", request.end)
                    DeviceToolResult(DeviceToolStatus.Ready, buildJsonObject { put("workouts", JsonArray(listOf(buildJsonObject { put("type", "Running"); put("durationMinutes", 35) }))) })
                }
                harness.timeZone = kotlinx.datetime.TimeZone.of("Europe/Dublin")
                harness.runner(agent, session.id, RoutineRun(routine)).run(session.id, routine.prompt).collect { event ->
                    if (event is AgentEvent.QuestionsAsked) harness.questions.respond(event.questionsId, mapOf("reflection" to listOf("Rested")))
                    assertFalse(event is AgentEvent.RunError, event.toString())
                }
                val note = assertNotNull(harness.knowledge.find("Journal/2026-10-05"))
                assertTrue("Running, 35 min" in note.body)
                assertEquals(1, harness.knowledge.changes().size)
            }
        } finally { harness.close() }
    }

    @Test
    fun refreshingTheGeneratedBlockPreservesUserWritingAndCanBeUndone() = runBlocking {
        val home = Files.createTempDirectory("journal-refresh").toFile()
        val harness = Harness(home = Path(home.path), modelFactory = { scriptedModel { _, _ -> emptyFlow() } })
        try {
            val old = "<!-- kinuvo-journal:start -->\nSample workout: 30 min\n<!-- kinuvo-journal:end -->"
            val body = "## My notes\nKeep my writing.\n\n## Kinuvo\n$old\n\n## Plans\nTomorrow's plans."
            val created = harness.knowledge.create("Journal", "2026-10-05", body = body)
            val tool = KnowledgeRewriteTool(harness.knowledge, ChangeSource("default", "test"))
            tool.execute(buildJsonObject { put("ref", created.ref); put("old", old); put("new", old.replace("30 min", "35 min")) })
            val updated = harness.knowledge.find(created.ref)!!.body
            assertTrue("Keep my writing." in updated && "Tomorrow's plans." in updated)
            assertEquals(1, Regex("kinuvo-journal:start").findAll(updated).count())
            assertTrue("35 min" in updated)
            harness.knowledge.undo(harness.knowledge.changes().first().id)
            assertEquals(created.body, harness.knowledge.find(created.ref)!!.body)
        } finally { harness.close() }
    }
}
