package kinuvo.harness.core.tools

import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.TestHome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryToolTest {
    private val home = TestHome()
    private val agent = home.agent
    private val tool = MemoryTool(agent, home.docs, userCap = 60)
    private suspend fun doc(doc: AgentDoc) = home.docs.read(agent.id, doc)

    private suspend fun call(vararg pairs: Pair<String, String>) =
        tool.execute(buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })

    @Test
    fun addReplaceRemove() = runTest {
        call("action" to "add", "target" to "memory", "content" to "User likes tea")
        call("action" to "add", "target" to "memory", "content" to "Project is\nHarness")
        call("action" to "replace", "target" to "memory", "old_text" to "tea", "content" to "User likes coffee")
        assertEquals("- User likes coffee\n- Project is Harness\n", doc(AgentDoc.Memory))

        call("action" to "remove", "target" to "memory", "old_text" to "Harness")
        assertEquals("- User likes coffee\n", doc(AgentDoc.Memory))
    }

    @Test
    fun batchIsAtomicAndChecksOnlyTheFinalSize() = runTest {
        call("action" to "add", "target" to "user", "content" to "x".repeat(40))
        tool.execute(Json.parseToJsonElement("""{"target":"user","operations":[
            {"action":"add","content":"New preference"},{"action":"remove","old_text":"xxxx"}
        ]}""").jsonObject)
        assertEquals("- New preference\n", doc(AgentDoc.UserNotes))
        for (failure in listOf("""{"action":"remove","old_text":"missing"}""", """{"action":"add","content":"${"x".repeat(60)}"}""")) {
            assertFailsWith<IllegalArgumentException> {
                tool.execute(Json.parseToJsonElement("""{"target":"user","operations":[
                    {"action":"replace","old_text":"New preference","content":"Changed"},$failure
                ]}""").jsonObject)
            }
            assertEquals("- New preference\n", doc(AgentDoc.UserNotes))
        }
    }

    @Test
    fun rejectsAmbiguousMatchAndOverCap() = runTest {
        call("action" to "add", "target" to "user", "content" to "Name: Sam")
        call("action" to "add", "target" to "user", "content" to "Name of dog: Rex")
        assertFailsWith<IllegalArgumentException> { call("action" to "remove", "target" to "user", "old_text" to "Name") }
        assertFailsWith<IllegalArgumentException> {
            call("action" to "add", "target" to "user", "content" to "x".repeat(60))
        }
        assertEquals("- Name: Sam\n- Name of dog: Rex\n", doc(AgentDoc.UserNotes))
    }
}
