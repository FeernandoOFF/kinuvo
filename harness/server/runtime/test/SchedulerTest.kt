package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.modelAlias
import kinuvo.harness.core.Model
import kinuvo.protocol.RoutineApprovals
import kinuvo.protocol.RoutineRan
import kinuvo.protocol.RunStatus
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionSettings
import kinuvo.protocol.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.uuid.Uuid
import kinuvo.harness.core.store.AgentFiles
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchedulerTest {
    private val home = Path(SystemTemporaryDirectory, "harness-scheduler-${Uuid.random()}")
    private var reply = "Here's your brief"
    private var toolCall: Pair<String, String>? = null
    private var hang = false
    /** Runs while the model is replying, e.g. to act like the user mid-run. */
    private var during: suspend () -> Unit = {}
    private val model = Model { _, history, _ ->
        val call = toolCall
        if (hang) return@Model flow<StreamFrame> { awaitCancellation() }
        if (call != null && history.none { it is SessionEntry.ToolResult }) flowOf(StreamFrame.ToolCallComplete("c1", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), call.first).modelAlias(), call.second), StreamFrame.End())
        else flow { during(); emit(StreamFrame.TextDelta(reply)); emit(StreamFrame.End()) }
    }
    private var now = 1_790_000_000_000L
    private val harness = runBlocking { HarnessRuntime(ServerInstallation.assemble(AgentFiles(home), clock = { now }), modelFactory = { model }) }.apply { timeZone = TimeZone.UTC }
    private val agent = runBlocking { harness.store.agents().single() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ran = mutableListOf<RoutineRan>()
    private val runs = Runs(harness.log, scope)
    private val scheduler = Scheduler(harness, runs, scope, onRan = { ran += it }, clock = { now })

    @AfterTest
    fun tearDown() = runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin(); harness.close(); kinuvo.harness.core.store.deleteTree(home) }

    private suspend fun tickAndWait() = withTimeout(10_000) { scheduler.tick(); scheduler.idle() }
    private suspend fun routine(id: String) = harness.routines.get(agent, id)!!

    @Test
    fun dueRoutineRunsInItsOwnSession() = runBlocking {
        val r = harness.routines.create(agent, "Brief", "Summarize the news", Trigger.Every(60))
        now += 60 * 60_000
        tickAndWait()

        val done = routine(r.id)
        assertEquals(RunStatus.Ok, done.lastStatus)
        assertEquals(now + 60 * 60_000, done.nextRunAt)
        val session = harness.log.session(agent, done.lastSessionId!!)
        assertEquals("Brief", session.title)
        assertEquals(r.id, session.routine?.id)
        val sent = harness.store.entries(agent, session.id).filterIsInstance<SessionEntry.UserMessage>().single()
        assertEquals("Summarize the news", sent.text)
        assertEquals(r.id, sent.routine?.id)
        assertEquals(listOf(RunStatus.Ok), ran.map { it.status })
        assertEquals(listOf(session.id), harness.log.page(agent, null, 10).sessions.map { it.id }, "A run with a reply joins the chats")
        assertEquals(1, harness.log.page(agent, null, 10, r.id).sessions.size)
    }

    @Test
    fun silentRunStaysInTheRoutinesHistoryOnly() = runBlocking {
        reply = "[SILENT]"
        val r = harness.routines.create(agent, "Watch", "Check the site", Trigger.Every(5))
        now += 5 * 60_000
        tickAndWait()

        assertEquals(RunStatus.Silent, routine(r.id).lastStatus)
        assertEquals(listOf(routine(r.id).lastSessionId), harness.log.page(agent, null, 10, r.id).sessions.map { it.id })
        assertTrue(harness.log.page(agent, null, 10).sessions.isEmpty())
        assertTrue(ran.isEmpty())
    }

    @Test
    fun runningRunIsShownOnTheRoutine() = runBlocking {
        hang = true
        val r = harness.routines.create(agent, "Brief", "Summarize", Trigger.Every(60))
        now += 60 * 60_000
        withTimeout(10_000) {
            scheduler.tick()
            while (routine(r.id).lastStatus != RunStatus.Running) delay(10)
        }
        val session = routine(r.id).lastSessionId!!
        assertEquals(now, routine(r.id).lastRunAt)
        assertEquals(session, harness.log.page(agent, null, 10, r.id).sessions.single().id)
        withTimeout(10_000) { runs.stop(session); scheduler.idle() }
        assertEquals(RunStatus.Stopped, routine(r.id).lastStatus)
    }

    @Test
    fun settingsPickedDuringASilentRunAreKept() = runBlocking {
        reply = "[SILENT]"
        val session = harness.newSession(agent).id
        harness.store.append(agent, session, SessionEntry.UserMessage("hi"))
        val before = harness.store.entries(agent, session)
        val picked = SessionSettings(model = "gpt-test")
        during = { harness.configure(agent, session, picked) }
        harness.routines.create(agent, "Watch", "Check", Trigger.Every(5)) { it.copy(sessionId = session) }
        now += 5 * 60_000
        tickAndWait()

        assertEquals(before + SessionEntry.Settings(picked), harness.store.entries(agent, session))
        assertEquals(picked, harness.store.session(agent, session).settings)
    }

    @Test
    fun silentRunInAnAttachedSessionIsRemovedFromIt() = runBlocking {
        reply = "[SILENT]"
        val session = harness.newSession(agent).id
        harness.store.append(agent, session, SessionEntry.UserMessage("hi"))
        val before = harness.store.entries(agent, session)
        val r = harness.routines.create(agent, "Watch", "Check", Trigger.Every(5)) { it.copy(sessionId = session) }
        now += 5 * 60_000
        tickAndWait()

        assertEquals(RunStatus.Silent, routine(r.id).lastStatus)
        assertEquals(before, harness.store.entries(agent, session))
    }

    @Test
    fun attachedSilentRunKeepsAnAnsweredRoutineInChats() = runBlocking {
        val first = harness.routines.create(agent, "Brief", "Summarize", Trigger.Every(60))
        now += 60 * 60_000
        tickAndWait()
        val session = routine(first.id).lastSessionId!!
        val before = harness.store.entries(agent, session)
        val changes = mutableListOf<kinuvo.protocol.SessionInfo>()
        harness.log.onChange { if (it.id == session) changes += it }
        reply = "[SILENT]"
        harness.routines.create(agent, "Watch", "Check", Trigger.Every(5)) { it.copy(sessionId = session) }
        now += 5 * 60_000
        tickAndWait()

        assertEquals(before, harness.store.entries(agent, session))
        assertTrue(changes.isNotEmpty() && changes.all { it.inChats })
        assertEquals(kinuvo.protocol.SessionStatus.Idle, changes.last().status)
        assertEquals(listOf(session), harness.log.page(agent, null, 10).sessions.map { it.id })
        harness.log.truncate(agent, session, 1)
        assertEquals(false, changes.last().inChats)
    }

    @Test
    fun stoppedRunIsRecordedAsStoppedWithoutANotice() = runBlocking {
        hang = true
        val r = harness.routines.create(agent, "Brief", "Summarize", Trigger.Every(60))
        now += 60 * 60_000
        withTimeout(10_000) {
            scheduler.tick()
            while (harness.store.sessions(agent).none { runs.isActive(it.id) }) delay(10)
            runs.stop(harness.store.sessions(agent).single().id)
            scheduler.idle()
        }

        assertEquals(RunStatus.Stopped, routine(r.id).lastStatus)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun missedRunsCatchUpOnceWithinGraceAndSkipOlderOnes() = runBlocking {
        val r = harness.routines.create(agent, "Hourly", "Check", Trigger.Every(60))
        now += 3 * 60 * 60_000 + 10 * 60_000 // three slots missed, the last one 10 minutes ago
        tickAndWait()
        assertEquals(RunStatus.Ok, routine(r.id).lastStatus)
        assertEquals(1, harness.store.sessions(agent).size)

        now += 60 * 60_000 + 40 * 60_000 // past the next slot's 30-minute grace
        tickAndWait()
        assertEquals(RunStatus.Skipped, routine(r.id).lastStatus)
        assertEquals(1, harness.store.sessions(agent).size)
        assertTrue(routine(r.id).nextRunAt!! > now)
    }

    @Test
    fun oneShotRunsOnceThenTurnsOff() = runBlocking {
        val r = harness.routines.create(agent, "Call", "Remind me to call", Trigger.Once(now + 60_000))
        now += 5 * 60_000
        tickAndWait()
        tickAndWait()

        assertEquals(RunStatus.Ok, routine(r.id).lastStatus)
        assertEquals(false, routine(r.id).enabled)
        assertEquals(1, harness.store.sessions(agent).size)
    }

    @Test
    fun denyModeAnswersApprovalsWithoutWaiting() = runBlocking {
        toolCall = "skill_manage" to """{"action":"create","name":"x","content":"---\nname: x\ndescription: d\n---\nbody"}"""
        val r = harness.routines.create(agent, "Unattended", "Make a skill", Trigger.Every(60)) { it.copy(approvals = RoutineApprovals.Deny) }
        now += 60 * 60_000
        tickAndWait()

        assertEquals(RunStatus.Ok, routine(r.id).lastStatus)
        val result = harness.store.entries(agent, routine(r.id).lastSessionId!!).filterIsInstance<SessionEntry.ToolResult>().single()
        assertTrue(result.isError && "denied" in result.output)
    }

    @Test
    fun runNowWorksWhilePausedAndKeepsTheSchedule() = runBlocking {
        val r = harness.routines.create(agent, "Brief", "Summarize", Trigger.Every(60)) { it.copy(enabled = false) }
        harness.routines.update(agent, r.id) { it.copy(runRequested = true) }
        tickAndWait()

        assertEquals(RunStatus.Ok, routine(r.id).lastStatus)
        assertEquals(false, routine(r.id).runRequested)
        assertNull(routine(r.id).nextRunAt)
    }

    @Test
    fun emptyHeartbeatIsSkipped() = runBlocking {
        harness.routines.update(agent, "heartbeat") { it.copy(enabled = true) }
        now += 60 * 60_000
        tickAndWait()
        assertEquals(RunStatus.Skipped, routine("heartbeat").lastStatus)

        harness.routines.update(agent, "heartbeat") { it.copy(prompt = "- anything due?", activeHours = null) }
        reply = "[SILENT]"
        now += 60 * 60_000
        tickAndWait()
        assertEquals(RunStatus.Silent, routine("heartbeat").lastStatus)
        assertNull(routine("heartbeat").lastSessionId)
        assertTrue(harness.store.sessions(agent).isEmpty(), "A silent Heartbeat leaves no Session")
    }
}
