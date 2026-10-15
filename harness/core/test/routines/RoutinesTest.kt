package kinuvo.harness.core.routines

import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.TestHome
import kinuvo.harness.core.ApprovalPolicy
import kinuvo.harness.core.PluginTools
import kinuvo.harness.core.Tolerance
import kinuvo.harness.core.Verdict
import kinuvo.harness.core.ownedBy
import kinuvo.protocol.PluginInstanceId
import kinuvo.harness.core.store.readText
import kinuvo.protocol.RoutineApprovals
import kinuvo.protocol.Trigger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class RoutinesTest {
    private val home = TestHome()
    private val agent = home.agent
    private val now = 1_790_000_000_000L
    private val zone = TimeZone.of("Europe/Dublin")
    private val routines = Routines(home.routineRepository, home.docs, defaultZone = { zone }, clock = { now })
    private val changes = mutableListOf<String>().also { list -> routines.onChange { agent, _ -> list += agent.id } }

    private fun args(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun everyAgentHasAnOffHeartbeat() = runTest {
        val heartbeat = routines.list(agent).single()
        assertTrue(heartbeat.heartbeat)
        assertFalse(heartbeat.enabled)
        assertNull(heartbeat.nextRunAt)
        assertTrue(routines.remove(agent, Routines.HEARTBEAT_ID))
        assertEquals(1, routines.list(agent).size)
    }

    @Test
    fun heartbeatPromptIsTheChecklistFile() = runTest {
        routines.update(agent, Routines.HEARTBEAT_ID) { it.copy(prompt = "- check mail", enabled = true) }
        assertEquals("- check mail", home.docs.read(agent.id, AgentDoc.Heartbeat))
        val heartbeat = routines.get(agent, Routines.HEARTBEAT_ID)!!
        assertEquals("- check mail", heartbeat.prompt)
        assertNotNull(heartbeat.nextRunAt)
        assertFalse(home.files.routines(agent.id).readText().contains("check mail"))
    }

    @Test
    fun createSchedulesAndPauseClearsNextRun() = runTest {
        val r = routines.create(agent, "Brief", "Summarize", Trigger.Every(60))
        assertEquals(now + 3_600_000, r.nextRunAt)
        assertEquals(zone.id, r.timeZone)
        val paused = routines.update(agent, r.id) { it.copy(enabled = false) }!!
        assertNull(paused.nextRunAt)
        assertEquals(listOf(agent.id, agent.id), changes)
        assertTrue(routines.remove(agent, r.id))
        assertNull(routines.get(agent, r.id))
    }

    @Test
    fun invalidRoutinesAreRejected() = runTest {
        assertFailsWith<IllegalArgumentException> { routines.create(agent, "x", "p", Trigger.Once(now - 1)) }
        assertFailsWith<IllegalArgumentException> { routines.create(agent, "x", "p", Trigger.Cron("nope")) }
        assertFailsWith<IllegalArgumentException> { routines.create(agent, "x", "p", Trigger.Every(5), "Mars/Base") }
        assertEquals(1, routines.list(agent).size)
    }

    @Test
    fun toolCreatesUpdatesAndAttaches() = runTest {
        val tool = ScheduleTool(agent, routines, { zone }, sessionId = "s1", clock = { now })
        tool.execute(args("action" to "create", "name" to "Brief", "prompt" to "Summarize", "cron" to "0 9 * * *", "active_hours" to "08:00-22:00"))
        val r = routines.list(agent).single { !it.heartbeat }
        assertEquals(Trigger.Cron("0 9 * * *"), r.trigger)
        assertEquals("08:00", r.activeHours?.start)

        tool.execute(args("action" to "update", "id" to r.id, "session" to "attach", "approvals" to "deny", "every" to "2h"))
        val updated = routines.get(agent, r.id)!!
        assertEquals("s1", updated.sessionId)
        assertEquals(RoutineApprovals.Deny, updated.approvals)
        assertEquals(Trigger.Every(120), updated.trigger)

        // Providers may send every optional parameter, blank when unused.
        tool.execute(args("action" to "create", "id" to "", "name" to "Call", "prompt" to "Remind me", "at" to "2026-09-25T09:00", "every" to "", "cron" to "", "session" to "", "approvals" to ""))
        assertTrue(routines.list(agent).any { it.name == "Call" && it.trigger is Trigger.Once })

        tool.execute(args("action" to "update", "id" to r.id, "session" to "", "active_hours" to "none"))
        assertEquals("s1", routines.get(agent, r.id)!!.sessionId)
        assertNull(routines.get(agent, r.id)!!.activeHours)
    }

    @Test
    fun schedulingRequiresApprovalBeforeIncreasingAuthority() = runTest {
        val tool = PluginTools(listOf(ScheduleTool(agent, routines, { zone })))
            .ownedBy(PluginInstanceId("kinuvo.core")).tools.single()
        for (tolerance in Tolerance.entries) {
            val policy = ApprovalPolicy(agent, home.trustRules, tolerance)
            for (action in listOf("list", "pause", "remove", "create", "update", "resume", "run")) {
                val verdict = policy.decide(tool, args("action" to action).toString())
                val runs = when (tolerance) {
                    Tolerance.Strict -> false
                    Tolerance.Default -> action in listOf("list", "pause", "remove")
                    Tolerance.Relaxed -> action in listOf("list", "pause", "remove", "create")
                    Tolerance.Full -> true
                }
                assertEquals(runs, verdict is Verdict.Run, "$tolerance $action")
            }
        }
        val policy = ApprovalPolicy(agent, home.trustRules)
        val create = assertIs<Verdict.Ask>(policy.decide(tool, args("action" to "create").toString()))
        policy.trust(tool, create.trustKeys)
        assertIs<Verdict.Run>(policy.decide(tool, args("action" to "create").toString()))
        for (action in listOf("update", "resume", "run")) {
            assertTrue(assertIs<Verdict.Ask>(policy.decide(tool, args("action" to action).toString())).trustKeys.isEmpty())
        }
    }

    @Test
    fun runsCanOnlyListOrRemoveThemselves() = runTest {
        val r = routines.create(agent, "Watch", "Check", Trigger.Every(5))
        val other = routines.create(agent, "Other", "Check", Trigger.Every(5))
        val tool = ScheduleTool(agent, routines, { zone }, routine = r, clock = { now })
        assertTrue(tool.execute(args("action" to "list")).contains("Watch"))
        assertFailsWith<IllegalStateException> { tool.execute(args("action" to "create", "name" to "x", "prompt" to "y", "every" to "5m")) }
        assertFailsWith<IllegalStateException> { tool.execute(args("action" to "remove", "id" to other.id)) }
        tool.execute(args("action" to "remove"))
        assertNull(routines.get(agent, r.id))
    }

    @Test
    fun instancesSharingARepositoryDontLoseChanges() = runTest {
        val other = Routines(home.routineRepository, home.docs, defaultZone = { zone }, clock = { now })
        routines.create(agent, "A", "p", Trigger.Every(60))
        other.create(agent, "B", "p", Trigger.Every(60))
        assertEquals(setOf("Heartbeat", "A", "B"), routines.list(agent).map { it.name }.toSet())
    }
}
