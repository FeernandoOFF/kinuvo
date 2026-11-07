package kinuvo.harness.core.agenttools

import kinuvo.harness.core.store.InMemoryStore
import kinuvo.harness.core.tools.Tool
import kinuvo.protocol.ExecutionKind
import kinuvo.protocol.LiveState
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentToolsTest {
    private val store = InMemoryStore()
    private val agents = listOf(
        AgentSummary("ada", "Ada", "Plans trips", ExecutionKind.Native, "codex", "gpt", false),
        AgentSummary("bob", "Bob", "", ExecutionKind.Acp, null, null, false),
        AgentSummary("far", "Far", "Elsewhere", ExecutionKind.Remote, null, null, false),
    )
    private val source = object : AgentToolsSource, AgentDelegation by FakeDelegation(store) {
        override suspend fun agents() = agents
        override suspend fun sessions(agentId: String) = store.sessions(agentId)
        override suspend fun delegated(agentId: String) = agents.flatMap { store.sessions(it.id) }.filter { it.parent?.agentId == agentId }
        override suspend fun entries(agentId: String, sessionId: String) = store.entries(agentId, sessionId)
        override suspend fun live(sessionId: String) = LiveState()
    }
    private val service = AgentTools(source)

    private suspend fun session(agent: String, vararg messages: String, hidden: Boolean = false): SessionInfo {
        val info = store.createSession(agent, "")
        messages.forEachIndexed { i, text ->
            store.append(agent, info.id, if (i % 2 == 0) SessionEntry.UserMessage(text) else SessionEntry.AssistantMessage(text))
        }
        if (hidden) store.append(agent, info.id, SessionEntry.Details(hidden = true))
        return info
    }

    private suspend fun call(scope: CallerScope, tool: String, args: JsonObject = JsonObject(emptyMap())): String =
        service.tools(scope).first { it.descriptor.name == tool }.execute(args)

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test fun readToolsAreLowRisk() {
        val tools: List<Tool> = service.tools(CallerScope("ada", "s"))
        assertEquals(setOf("agents_list", "sessions_list", "sessions_search", "sessions_read", "session_status", "delegate", "delegate_wait", "subagents"), tools.map { it.descriptor.name }.toSet())
        assertEquals(setOf("delegate"), tools.filter { it.risk != kinuvo.protocol.Risk.Low }.map { it.descriptor.name }.toSet())
    }

    @Test fun agentsListSkipsRemoteAgents() = runTest {
        val text = call(CallerScope("ada", null), "agents_list")
        assertTrue("ada \"Ada\" (you)" in text)
        assertTrue("bob" in text && "external" in text)
        assertFalse("far" in text)
    }

    @Test fun callersSeeOnlyTheirOwnSessions() = runTest {
        val mine = session("ada", "my secret plan")
        val theirs = session("bob", "bob's secret plan")
        val ada = CallerScope("ada", mine.id)
        val listed = call(ada, "sessions_list")
        assertTrue(mine.id in listed)
        assertFalse(theirs.id in listed)
        assertFalse("bob's" in call(ada, "sessions_search", args("query" to "secret")))
        assertFailsWith<IllegalArgumentException> { call(ada, "sessions_read", args("session_id" to theirs.id)) }
        assertFailsWith<IllegalArgumentException> { call(ada, "session_status", args("session_id" to theirs.id)) }
        assertFailsWith<IllegalArgumentException> { call(ada, "sessions_search", args("query" to "secret", "session_id" to theirs.id)) }
    }

    @Test fun hiddenSessionsAreExcludedEverywhere() = runTest {
        val visible = session("ada", "launch plan")
        val hidden = session("ada", "launch password", hidden = true)
        val scope = CallerScope("ada", hidden.id)
        assertFalse(hidden.id in call(scope, "sessions_list"))
        assertTrue(visible.id in call(scope, "sessions_list"))
        assertFalse("password" in call(scope, "sessions_search", args("query" to "launch")))
        assertFailsWith<IllegalArgumentException> { call(scope, "sessions_read", args("session_id" to hidden.id)) }
        assertFailsWith<IllegalArgumentException> { call(scope, "session_status", args("session_id" to hidden.id)) }
    }

    @Test fun listFiltersByTitleAndPages() = runTest {
        val first = session("ada", "Trip to Lisbon")
        val second = session("ada", "Taxes 2026")
        val scope = CallerScope("ada", null)
        val lisbon = call(scope, "sessions_list", args("query" to "lisbon"))
        assertTrue(first.id in lisbon && second.id !in lisbon)
        val page = call(scope, "sessions_list", args("limit" to "1"))
        val cursor = page.lines().last().removePrefix("next_cursor: ")
        val next = call(scope, "sessions_list", args("cursor" to cursor, "limit" to "1"))
        assertEquals(setOf(first.id, second.id), listOf(page, next).map { text -> listOf(first.id, second.id).single { it in text } }.toSet())
        assertFalse("next_cursor" in next)
    }

    @Test fun searchReturnsExcerptsWithMessageRefs() = runTest {
        val s = session("ada", "book a flight to Lisbon", "Booked: Lisbon on Friday", "thanks")
        val hits = call(CallerScope("ada", null), "sessions_search", args("query" to "lisbon"))
        assertTrue("${s.id} " in hits && "#1 user" in hits && "#2 assistant" in hits)
        assertEquals("No matches.", call(CallerScope("ada", null), "sessions_search", args("query" to "paris")))
    }

    @Test fun readPagesAroundAMessage() = runTest {
        val s = session("ada", *(1..30).map { "message $it" }.toTypedArray())
        val scope = CallerScope("ada", null)
        val last = call(scope, "sessions_read", args("session_id" to s.id, "limit" to "5"))
        assertTrue("message 30" in last && "message 25" !in last && "earlier: before=#26" in last && "later:" !in last)
        val around = call(scope, "sessions_read", args("session_id" to s.id, "around" to "#10", "limit" to "4"))
        assertTrue("message 9" in around && "message 10" in around && "message 12" !in around)
        val start = call(scope, "sessions_read", args("session_id" to s.id, "from" to "start", "limit" to "2"))
        assertTrue("message 1" in start && "later: after=#2" in start)
    }

    @Test fun statusReportsStateAndUsage() = runTest {
        val s = session("ada", "hi", "hello")
        val text = call(CallerScope("ada", s.id), "session_status")
        assertTrue("state: idle" in text && "messages: 2" in text)
    }
}
