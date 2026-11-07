package kinuvo.harness.core.agenttools

import kinuvo.harness.core.store.InMemoryStore
import kinuvo.protocol.ExecutionKind
import kinuvo.protocol.LiveState
import kinuvo.protocol.Risk
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DelegationTest {
    private val store = InMemoryStore()
    private val fake = FakeDelegation(store)
    private val agents = listOf(
        AgentSummary("ada", "Ada", "", ExecutionKind.Native, "codex", "gpt", false),
        AgentSummary("bob", "Bob", "", ExecutionKind.Acp, null, null, false),
        AgentSummary("loc", "Local", "", ExecutionKind.Native, "local", "small", true),
    )
    private val running = mutableSetOf<String>()
    private val source = object : AgentToolsSource, AgentDelegation by fake {
        override suspend fun agents() = agents
        override suspend fun sessions(agentId: String) = store.sessions(agentId)
        override suspend fun delegated(agentId: String) = agents.flatMap { store.sessions(it.id) }.filter { it.parent?.agentId == agentId }
        override suspend fun entries(agentId: String, sessionId: String) = store.entries(agentId, sessionId)
        override suspend fun live(sessionId: String) = LiveState(running = sessionId in running)
    }

    private suspend fun parent(agent: String = "ada") = CallerScope(agent, store.createSession(agent, "").id)

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) -> when (v) { is JsonElement -> put(k, v); is Boolean -> put(k, v); else -> put(k, v.toString()) } }
    }

    private fun task(goal: String, agent: String? = null, hidden: Boolean = false) = buildJsonObject {
        put("goal", goal); agent?.let { put("agent", it) }; if (hidden) put("hidden", true)
    }

    private fun service(scope: kotlinx.coroutines.CoroutineScope) = AgentTools(source, scope)

    @Test fun foregroundWaitsForTheChildsFinalAnswerAndGivesItOnlyTheGoalAndContext() = runTest {
        val service = service(backgroundScope)
        val parent = parent()
        val call = async { service.tools(parent).first { it.descriptor.name == "delegate" }.execute(args("agent" to "bob", "goal" to "Book a table", "context" to "For two, Friday")) }
        runCurrent()
        assertFalse(call.isCompleted)
        val child = store.sessions("bob").single()
        assertEquals(listOf(SessionEntry.UserMessage("Book a table\n\nContext:\nFor two, Friday")), store.entries("bob", child.id).filterIsInstance<SessionEntry.UserMessage>().map { it.copy(writtenAt = null) })
        assertEquals(parent.sessionId, child.parent?.sessionId)
        assertEquals(1, child.parent?.depth)
        fake.answer(child.id, "Booked for 19:00")
        assertEquals("Booked for 19:00\n\n(Session ${child.id} on bob)", call.await())
    }

    @Test fun theAgentDefaultsToTheCallersOwn() = runTest {
        val service = service(backgroundScope)
        val parent = parent("ada")
        val call = async { service.tools(parent).first { it.descriptor.name == "delegate" }.execute(args("goal" to "think")) }
        runCurrent()
        assertEquals("ada", fake.started.single().agentId)
        fake.answer(store.sessions("ada").single { it.parent != null }.id, "done")
        assertTrue(call.await().startsWith("done"))
    }

    @Test fun aFailedChildFailsTheCall() = runTest {
        val service = service(backgroundScope)
        val parent = parent()
        val call = async { runCatching { service.tools(parent).first { it.descriptor.name == "delegate" }.execute(args("agent" to "bob", "goal" to "x")) } }
        runCurrent()
        fake.answer(store.sessions("bob").single().id, "Failed: boom", failed = true)
        assertEquals("Failed: boom", call.await().exceptionOrNull()?.message)
    }

    @Test fun backgroundReturnsAtOnceAndDelegateWaitHandsOverFinishedResultsOnce() = runTest {
        val service = service(backgroundScope)
        val parent = parent()
        val started = service.tools(parent).first { it.descriptor.name == "delegate" }.execute(args("agent" to "bob", "goal" to "slow job", "background" to true))
        val child = store.sessions("bob").single()
        assertTrue(child.id in started)
        val wait = service.tools(parent).first { it.descriptor.name == "delegate_wait" }
        assertEquals("Still running: ${child.id}", wait.execute(args("timeout_seconds" to 1)))
        fake.answer(child.id, "all done")
        assertEquals("Session ${child.id}:\nall done", wait.execute(args()))
        assertEquals("No children running in the background.", wait.execute(args()))
    }

    @Test fun delegateWaitCanReturnOnTheFirstFinishedChild() = runTest {
        val service = service(backgroundScope)
        val parent = parent()
        val delegate = service.tools(parent).first { it.descriptor.name == "delegate" }
        delegate.execute(args("agent" to "bob", "goal" to "first", "background" to true))
        delegate.execute(args("agent" to "loc", "goal" to "second", "background" to true))
        val (a, b) = listOf("bob", "loc").map { store.sessions(it).single().id }
        val wait = service.tools(parent).first { it.descriptor.name == "delegate_wait" }
        val first = async { wait.execute(args("any" to true)) }
        runCurrent()
        fake.answer(b, "quick")
        val text = first.await()
        assertTrue("Session $b:\nquick" in text && "Still running: $a" in text)
    }

    @Test fun delegateWaitOnlyKnowsTheCallersOwnChildren() = runTest {
        val service = service(backgroundScope)
        val mine = parent()
        service.tools(mine).first { it.descriptor.name == "delegate" }.execute(args("agent" to "bob", "goal" to "x", "background" to true))
        val other = parent()
        assertEquals("No children running in the background.", service.tools(other).first { it.descriptor.name == "delegate_wait" }.execute(args()))
    }

    @Test fun aBatchRunsInParallelAndReportsEachTask() = runTest {
        val service = service(backgroundScope)
        val parent = parent()
        val tasks = JsonArray(listOf(task("one", "bob"), task("two", "loc"), task("three", "ada")))
        val call = async { service.tools(parent).first { it.descriptor.name == "delegate" }.execute(args("tasks" to tasks)) }
        runCurrent()
        assertEquals(3, fake.started.size)
        store.sessions("bob").single().let { fake.answer(it.id, "answer one") }
        store.sessions("loc").single().let { fake.answer(it.id, "answer two") }
        store.sessions("ada").single { it.parent != null }.let { fake.answer(it.id, "answer three") }
        val text = call.await()
        assertTrue("Task 1 (bob): answer one" in text && "Task 2 (loc): answer two" in text && "Task 3 (ada): answer three" in text)
    }

    @Test fun aBatchIsCapped() = runTest {
        val service = service(backgroundScope)
        val tasks = JsonArray((1..5).map { task("t$it") })
        assertFailsWith<IllegalArgumentException> { service.tools(parent()).first { it.descriptor.name == "delegate" }.execute(args("tasks" to tasks)) }
        assertTrue(fake.started.isEmpty())
    }

    @Test fun unknownAgentsAreRefusedBeforeAnythingStarts() = runTest {
        val service = service(backgroundScope)
        assertFailsWith<IllegalArgumentException> { service.tools(parent()).first { it.descriptor.name == "delegate" }.execute(args("agent" to "nobody", "goal" to "x")) }
        assertTrue(fake.started.isEmpty())
    }

    @Test fun childrenAtTheDepthLimitGetNoDelegationTools() {
        val service = service(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()))
        val names = { depth: Int, max: Int -> service.tools(CallerScope("ada", "s", depth), max).map { it.descriptor.name }.toSet() }
        assertTrue("delegate" in names(0, 2) && "delegate" in names(1, 2))
        assertTrue(names(2, 2).none { it in setOf("delegate", "delegate_wait", "subagents") })
        assertTrue("sessions_list" in names(2, 2))
        assertTrue("delegate" !in names(0, 0))
    }

    @Test fun cancellingTheCallStopsTheChild() = runTest {
        val service = service(backgroundScope)
        val parent = parent()
        val call = async { service.tools(parent).first { it.descriptor.name == "delegate" }.execute(args("agent" to "bob", "goal" to "long")) }
        runCurrent()
        val child = store.sessions("bob").single().id
        call.cancelAndJoin()
        assertEquals(listOf(child), fake.stopped)
    }

    @Test fun stoppingASessionStopsItsRunningChildren() = runTest {
        val service = service(backgroundScope)
        val parent = parent()
        val delegate = service.tools(parent).first { it.descriptor.name == "delegate" }
        delegate.execute(args("agent" to "bob", "goal" to "one", "background" to true))
        delegate.execute(args("agent" to "loc", "goal" to "two", "background" to true))
        service.stopChildren(parent.sessionId!!)
        assertEquals(setOf("bob", "loc"), fake.stopped.map { id -> listOf("bob", "loc").first { store.sessions(it).any { s -> s.id == id } } }.toSet())
    }

    @Test fun subagentsListsChildrenWithMetadataOnlyAndCancelsThem() = runTest {
        val service = service(backgroundScope)
        val parent = parent()
        val delegate = service.tools(parent).first { it.descriptor.name == "delegate" }
        delegate.execute(args("agent" to "bob", "goal" to "plain job", "background" to true))
        delegate.execute(args("agent" to "loc", "goal" to "secret job", "hidden" to true, "background" to true))
        val bob = store.sessions("bob").single().id
        val loc = store.sessions("loc").single().id
        running += bob
        val subagents = service.tools(parent).first { it.descriptor.name == "subagents" }
        val list = subagents.execute(args())
        assertTrue("- $bob bob \"plain job\" [running]" in list)
        assertTrue("- $loc loc \"secret job\" [finished, hidden]" in list)
        assertEquals(Risk.Low, subagents.assess(args()).risk)
        assertEquals(Risk.Medium, subagents.assess(args("action" to "cancel")).risk)
        assertEquals("Stopped $bob.", subagents.execute(args("action" to "cancel", "session_id" to bob)))
        assertEquals(listOf(bob), fake.stopped)
        assertFailsWith<IllegalArgumentException> { subagents.execute(args("action" to "cancel", "session_id" to store.sessions("ada").firstOrNull()?.id.orEmpty())) }
        assertFailsWith<IllegalArgumentException> { service.tools(parent()).first { it.descriptor.name == "subagents" }.execute(args("action" to "cancel", "session_id" to loc)) }
        assertEquals("No children.", service.tools(parent()).first { it.descriptor.name == "subagents" }.execute(args()))
    }

    @Test fun delegatingIsMediumRiskAndWaitingIsLow() = runTest {
        val tools = service(backgroundScope).tools(parent()).associateBy { it.descriptor.name }
        assertEquals(Risk.Medium, tools.getValue("delegate").assess(args()).risk)
        assertEquals(Risk.Low, tools.getValue("delegate_wait").risk)
    }

    @Test fun theParentSeesAVisibleChildButNeverAHiddenOnesTranscript() = runTest {
        val service = service(backgroundScope)
        val parent = parent()
        val delegate = service.tools(parent).first { it.descriptor.name == "delegate" }
        delegate.execute(args("agent" to "bob", "goal" to "open topic", "background" to true))
        delegate.execute(args("agent" to "loc", "goal" to "private topic", "hidden" to true, "background" to true))
        val open = store.sessions("bob").single().id
        val hidden = store.sessions("loc").single().id
        suspend fun call(name: String, a: JsonObject) = service.tools(parent).first { it.descriptor.name == name }.execute(a)
        assertTrue(open in call("sessions_list", args()))
        assertFalse(hidden in call("sessions_list", args()))
        assertTrue("open topic" in call("sessions_read", args("session_id" to open)))
        assertFailsWith<IllegalArgumentException> { call("sessions_read", args("session_id" to hidden)) }
        assertFailsWith<IllegalArgumentException> { call("session_status", args("session_id" to hidden)) }
        assertFalse("private" in call("sessions_search", args("query" to "topic")))
        assertTrue("open topic" in call("sessions_search", args("query" to "topic")))
    }

    @Test fun anotherAgentDoesNotSeeTheChildren() = runTest {
        val service = service(backgroundScope)
        val parent = parent("ada")
        service.tools(parent).first { it.descriptor.name == "delegate" }.execute(args("agent" to "bob", "goal" to "open topic", "background" to true))
        val bob = store.sessions("bob").single().id
        val stranger = CallerScope("loc", null)
        assertFalse(bob in service.tools(stranger).first { it.descriptor.name == "sessions_list" }.execute(args()))
    }

    @Test fun reviewShowsTheUserAHiddenResultAndWithholdsItOnDeny() = runTest {
        val service = service(backgroundScope)
        val parent = parent()
        val delegate = service.tools(parent).first { it.descriptor.name == "delegate" }
        val allowed = async { delegate.execute(args("agent" to "loc", "goal" to "g", "hidden" to true, "review" to true), "call-1") }
        runCurrent()
        fake.answer(store.sessions("loc").single().id, "sensitive result")
        assertTrue(allowed.await().startsWith("sensitive result"))
        assertEquals(listOf("sensitive result"), fake.reviewed)

        fake.allow = false
        val denied = async { delegate.execute(args("agent" to "loc", "goal" to "g2", "hidden" to true, "review" to true), "call-2") }
        runCurrent()
        fake.answer(store.sessions("loc").first { it.title == "g2" }.id, "another secret")
        assertTrue(denied.await().startsWith("The user withheld the result."))
        assertFalse("another secret" in denied.await())
    }

    @Test fun reviewIsSkippedForVisibleChildren() = runTest {
        val service = service(backgroundScope)
        val parent = parent()
        val call = async { service.tools(parent).first { it.descriptor.name == "delegate" }.execute(args("agent" to "bob", "goal" to "g", "review" to true)) }
        runCurrent()
        fake.answer(store.sessions("bob").single().id, "plain")
        assertTrue(call.await().startsWith("plain"))
        assertTrue(fake.reviewed.isEmpty())
    }
}
