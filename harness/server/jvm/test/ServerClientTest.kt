package kinuvo.harness.server

import kinuvo.harness.core.modelAlias
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.client.AuthException
import kinuvo.client.KinuvoClient
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ApprovalDecision
import kinuvo.harness.server.Harness
import kinuvo.harness.core.Model
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.exists
import kinuvo.harness.core.store.FileStore
import kinuvo.harness.core.store.Store
import kinuvo.protocol.SessionEntry
import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.core.PrivateFile
import kinuvo.harness.codex.CodexProvider
import kinuvo.protocol.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun KinuvoClient.approvals() =
    events(Api.Update).mapNotNull { (it.change as? SessionChange.Event)?.event as? AgentEvent.ApprovalRequested }

private fun KinuvoClient.statuses(sessionId: String) = events(Api.SessionChanged).filter { it.id == sessionId }.map { it.status }

private suspend fun KinuvoClient.newSession() = call(Api.CreateSession, AgentRef("default")).id

/** What a catch-up sent: the window start when it was reset, the entries and the live state. */
private data class CaughtUp(val reset: Int?, val entries: List<SessionEntry>, val live: LiveState)

/** Subscribes to [sessionId] from [after] and collects the catch-up. */
private suspend fun KinuvoClient.catchUp(sessionId: String, after: Int? = 0, limit: Int = 100): CaughtUp = coroutineScope {
    val changes = async(start = CoroutineStart.UNDISPATCHED) {
        events(Api.Update).filter { it.sessionId == sessionId }.map { it.change }.transformWhile { emit(it); it !is SessionChange.Live }.toList()
    }
    call(Api.CatchUp, CatchUpParams("default", sessionId, after, limit))
    val all = changes.await()
    CaughtUp(
        all.filterIsInstance<SessionChange.Reset>().singleOrNull()?.firstSeq,
        all.filterIsInstance<SessionChange.Entry>().map { it.entry },
        (all.last() as SessionChange.Live).state,
    )
}

class ServerClientTest {
    private val home = Files.createTempDirectory("harness-server").toFile()

    /** First turn: call skill_manage (needs an Approval); after the tool result: answer "done". */
    private val model = Model { _, history, _ ->
        if (history.none { it is SessionEntry.ToolResult }) flowOf(
            StreamFrame.ToolCallComplete("c1", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "skill_manage").modelAlias(), """{"action":"create","name":"x","content":"---\nname: x\ndescription: d\n---\nbody"}"""),
            StreamFrame.End(),
        ) else flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
    }

    @Test
    fun harnessAcceptsStoreInterface() = runBlocking {
        val files = FileStore(AgentFiles(Path(home.path)))
        val store = object : Store by files {}
        val session = Harness(Path(home.path), store).newSession(store.agents().single())
        assertEquals(session.id, store.sessions(store.agents().single()).single().id)
    }

    @Test
    fun embeddedServerAcceptsPhonePairing() = runBlocking {
        val server = HarnessServer(Harness(home = Path(home.path)))
        val port = server.start("0.0.0.0", 0)
        try {
            assertEquals(lanAddresses(), server.auth.pairing(false)!!.addresses)
            val phone = KinuvoClient("ws://127.0.0.1:$port/ws", "phone", pairingCode = server.auth.currentPairingCode())
            withTimeout(10_000) { phone.connect() }
            assertEquals("phone", server.auth.devices().single().name)
            phone.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun pairingAdvertisesOnlyTheBoundInterface() = runBlocking {
        val host = lanAddresses().firstOrNull() ?: return@runBlocking
        val server = HarnessServer(Harness(home = Path(home.path)))
        server.start(host, 0, schedule = false)
        try {
            assertEquals(listOf(host), server.auth.pairing(false)!!.addresses)
        } finally {
            server.stop()
        }
    }

    @Test
    fun loopbackBindingClearsPreviouslyAdvertisedAddresses() = runBlocking {
        val server = HarnessServer(Harness(home = Path(home.path)))
        server.auth.addresses = { listOf("192.168.1.2", "100.82.82.99") }
        server.start("127.0.0.1", 0, schedule = false)
        try {
            assertEquals(emptyList(), server.auth.pairing(false)!!.addresses)
        } finally {
            server.stop()
        }
    }

    @Test
    fun pairedDevicesPairOthersAndRevokeThem() = runBlocking {
        val server = HarnessServer(Harness(home = Path(home.path)))
        val port = server.start("127.0.0.1", 0)
        val url = "ws://127.0.0.1:$port/ws"
        try {
            withTimeout(10_000) {
                var phoneToken: String? = null
                val phone = KinuvoClient(url, "phone", pairingCode = server.auth.currentPairingCode(), onToken = { phoneToken = it })
                phone.connect()
                val code = phone.call(Api.Pairing, PairingParams())
                assertEquals(port, code.port)
                val refreshed = phone.call(Api.Pairing, PairingParams(refresh = true))
                assertTrue(code.code != refreshed.code)
                val laptop = KinuvoClient(url, "laptop", pairingCode = refreshed.code)
                laptop.connect()

                val devices = phone.call(Api.PairedDevices, Unit)
                assertEquals(listOf("phone" to true, "laptop" to false), devices.map { it.name to it.current })
                assertTrue(devices.all { it.connected })
                assertTrue(devices.all { it.lastSeen != null })

                val changed = async(start = CoroutineStart.UNDISPATCHED) { phone.events(Api.DevicesChanged).first() }
                phone.call(Api.RevokeDevice, DeviceRef(devices.single { it.name == "laptop" }.id))
                changed.await()
                assertEquals(listOf("phone"), server.auth.devices().map { it.name })
                laptop.state.first { it is kinuvo.client.ConnectionState.Disconnected }
                laptop.close()

                phone.call(Api.RevokeDevice, DeviceRef(devices.single { it.current }.id))
                phone.state.first { it is kinuvo.client.ConnectionState.Disconnected && it.rejected }
                phone.close()
                assertFailsWith<AuthException> { KinuvoClient(url, "phone", token = phoneToken).connect() }
                Unit
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun managesRoutinesAndReportsTheirRuns() = runBlocking {
        val server = HarnessServer(Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))), modelFactory = { Model { _, _, _ -> flowOf(StreamFrame.TextDelta("hello"), StreamFrame.End()) } }))
        val port = server.start("127.0.0.1", 0)
        try {
            withTimeout(30_000) {
                val client = KinuvoClient("ws://127.0.0.1:$port/ws", "desk", pairingCode = server.auth.currentPairingCode(), timeZone = "America/New_York")
                client.connect()
                assertEquals("America/New_York", server.harness.timeZone.id)
                val heartbeat = client.call(Api.Routines, AgentRef("default")).single()
                assertEquals("America/New_York", heartbeat.timeZone)

                val changed = async(start = CoroutineStart.UNDISPATCHED) { client.events(Api.RoutinesChanged).first() }
                val updated = client.call(Api.UpdateRoutine, UpdateRoutineParams("default", heartbeat.id, RoutineSpec(approvals = RoutineApprovals.Auto)))
                assertEquals(RoutineApprovals.Auto, updated.approvals)
                assertEquals(updated, changed.await().routines.single())

                val routine = server.harness.routines.create(server.harness.store.agents().single(), "Greet", "Say hello", kinuvo.protocol.Trigger.Every(60))
                val ran = async(start = CoroutineStart.UNDISPATCHED) { client.events(Api.RoutineRan).first() }
                client.call(Api.RunRoutine, RoutineParams("default", routine.id))
                val result = ran.await()
                assertEquals(RunStatus.Ok, result.status)
                val session = client.call(Api.Session, SessionParams("default", result.sessionId!!))
                assertEquals(RoutineRef(routine.id, "Greet"), session.routine)
                assertEquals(listOf(session.id), client.call(Api.Sessions, SessionsParams("default", routineId = routine.id)).sessions.map { it.id })
                assertEquals(listOf(session.id), client.call(Api.Sessions, SessionsParams("default")).sessions.map { it.id })

                client.call(Api.RemoveRoutine, RoutineParams("default", routine.id))
                assertEquals(listOf("heartbeat"), client.call(Api.Routines, AgentRef("default")).map { it.id })
                client.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun advertisesItselfToTheCliOwnerOnly() = runBlocking {
        val server = HarnessServer(Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json")))))))
        server.start("0.0.0.0", 0, schedule = false)
        try {
            server.advertise()
            val local = LocalServer.read(Path(home.path))!!
            assertEquals("127.0.0.1", local.host)
            assertTrue(local.alive)
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(java.nio.file.Path.of(LocalServer.file(Path(home.path)).toString()))))
        } finally {
            server.stop()
        }
        assertEquals(false, LocalServer.file(Path(home.path)).exists())
    }

    @Test
    fun keepsToTheFirstFreePortOfItsList() = runBlocking {
        java.net.ServerSocket(0).use { taken ->
            val free = java.net.ServerSocket(0).use { it.localPort }
            val server = HarnessServer(Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json")))))))
            try {
                assertEquals(free, server.start("0.0.0.0", listOf(taken.localPort, free), schedule = false))
            } finally {
                server.stop()
            }
            val fallback = HarnessServer(Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json")))))))
            try {
                assertTrue(fallback.start("0.0.0.0", listOf(taken.localPort), schedule = false) != taken.localPort)
            } finally {
                fallback.stop()
            }
        }
    }

    @Test
    fun managesAgentsSkillsMcpServersAndRoutines() = runBlocking {
        val server = HarnessServer(Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json")))))))
        val port = server.start("127.0.0.1", 0, schedule = false)
        try {
            withTimeout(30_000) {
                val client = KinuvoClient("ws://127.0.0.1:$port/ws", "cli", token = server.auth.sessionToken(), timeZone = "UTC")
                client.connect()

                val agentsChanged = async(start = CoroutineStart.UNDISPATCHED) { client.events(Api.AgentsChanged).first() }
                assertEquals(AgentInfo("work", "Work", "none"), client.call(Api.CreateAgent, CreateAgentParams("work", name = "Work", cloneFrom = "default")))
                assertEquals(listOf("default", "work"), agentsChanged.await().map { it.id })
                val details = client.call(Api.UpdateAgent, UpdateAgentParams("work", model = "gpt-x", tools = listOf("memory"), soul = "Be brief.", avatar = "Lilac Sprout", userNotes = "Name: Alex\nOccupation: Designer"))
                assertEquals(AgentSettings("gpt-x", "medium", "default", "none", tools = listOf("memory")), details.settings)
                assertEquals("Be brief.", client.call(Api.Agent, AgentRef("work")).soul)
                assertEquals("Lilac Sprout", client.call(Api.Agents, Unit).first { it.id == "work" }.avatar)
                assertEquals("Name: Alex\nOccupation: Designer", client.call(Api.Agent, AgentRef("work")).userNotes)
                assertTrue(client.call(Api.Agent, AgentRef("work")).identity.startsWith("# Work"))
                assertFailsWith<IllegalStateException> { client.call(Api.UpdateAgent, UpdateAgentParams("work", exec = "moon")) }
                val renamed = async(start = CoroutineStart.UNDISPATCHED) { client.events(Api.AgentsChanged).first() }
                client.call(Api.UpdateAgent, UpdateAgentParams("work", name = "Office"))
                assertEquals("Office", renamed.await().first { it.id == "work" }.name)

                val skill = "---\nname: notes\ndescription: Take notes\n---\nWrite them down."
                client.call(Api.SaveSkill, SaveSkillParams("work", "notes", skill, ConfigScope.Shared))
                client.call(Api.SaveSkill, SaveSkillParams("work", "notes", skill, files = mapOf("ref/a.md" to "A")))
                val skills = client.call(Api.Skills, AgentRef("work")).filter { it.name == "notes" }
                assertEquals(listOf(ConfigScope.Agent to true, ConfigScope.Shared to false), skills.map { it.scope to it.active })
                assertEquals(listOf("ref/a.md"), client.call(Api.Skill, SkillParams("work", "notes")).files)
                assertFailsWith<IllegalStateException> { client.call(Api.SaveSkill, SaveSkillParams("work", "notes", skill, files = mapOf("../x" to ""))) }
                client.call(Api.DeleteSkill, SkillParams("work", "notes"))
                assertEquals(ConfigScope.Shared, client.call(Api.Skill, SkillParams("work", "notes")).info.scope)

                client.call(Api.SaveMcp, SaveMcpParams("work", McpServerInfo("docs", ConfigScope.Shared, url = "http://127.0.0.1:1/mcp")))
                client.call(Api.SaveMcp, SaveMcpParams("work", McpServerInfo("docs", command = "definitely-not-a-command", args = listOf("--x"), env = mapOf("K" to "\${K}"))))
                val mcp = client.call(Api.McpServers, AgentRef("work"))
                assertEquals(listOf(ConfigScope.Agent to true, ConfigScope.Shared to false), mcp.map { it.scope to it.active })
                assertEquals(mapOf("K" to "\${K}"), mcp.first().env)
                assertTrue(client.call(Api.TestMcp, McpParams("work", "docs")).error != null)
                client.call(Api.RemoveMcp, McpParams("work", "docs"))
                assertEquals(listOf(ConfigScope.Shared to true), client.call(Api.McpServers, AgentRef("work")).map { it.scope to it.active })

                val routine = client.call(Api.CreateRoutine, CreateRoutineParams("work", RoutineSpec(name = "Brief", prompt = "Summarize", cron = "0 9 * * *", activeHours = "08:00-20:00")))
                assertEquals(Trigger.Cron("0 9 * * *"), routine.trigger)
                assertEquals("UTC", routine.timeZone)
                val edited = client.call(Api.UpdateRoutine, UpdateRoutineParams("work", routine.id, RoutineSpec(every = "2h", activeHours = "none", enabled = false)))
                assertEquals(Trigger.Every(120), edited.trigger)
                assertEquals(null, edited.activeHours)
                assertEquals(false, edited.enabled)
                assertFailsWith<IllegalStateException> { client.call(Api.UpdateRoutine, UpdateRoutineParams("work", routine.id, RoutineSpec(session = "nope"))) }
                // Without the Scheduler nothing would pick a "run now" up.
                assertFailsWith<IllegalStateException> { client.call(Api.RunRoutine, RoutineParams("work", routine.id)) }

                client.call(Api.DeleteAgent, AgentRef("work"))
                assertEquals(listOf("default"), client.call(Api.Agents, Unit).map { it.id })
                assertFailsWith<IllegalStateException> { client.call(Api.DeleteAgent, AgentRef("default")) }
                client.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun pairsStreamsRunsAndAnswersApprovals() = runBlocking {
        val server = HarnessServer(Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))), modelFactory = { model }))
        val port = server.start("127.0.0.1", 0)
        val url = "ws://127.0.0.1:$port/ws"
        try {
            withTimeout(30_000) {
                assertFailsWith<AuthException> { KinuvoClient(url, "bad", pairingCode = "000000").connect() }

                var saved: String? = null
                val client = KinuvoClient(url, "test-phone", pairingCode = server.auth.currentPairingCode(), onToken = { saved = it })
                client.connect()
                assertEquals("test-phone", server.auth.devices().single().name)

                val agents = client.call(Api.Agents, Unit)
                assertEquals(listOf("default"), agents.map { it.id })

                val sessionId = client.newSession()
                client.catchUp(sessionId)
                val streamed = async(start = CoroutineStart.UNDISPATCHED) {
                    client.events(Api.Update).map { it.change }.transformWhile { emit(it); (it as? SessionChange.Event)?.event !is AgentEvent.RunFinished }.toList()
                }
                val statuses = async(start = CoroutineStart.UNDISPATCHED) {
                    client.statuses(sessionId).transformWhile { emit(it); it != SessionStatus.Idle }.toList()
                }
                val approvalRequest = async(start = CoroutineStart.UNDISPATCHED) { client.approvals().first() }
                val sent = client.call(Api.Send, SendParams("default", sessionId, "make a skill"))
                val approval = approvalRequest.await()
                assertEquals("c1", approval.toolCallId)
                assertTrue(client.call(Api.Approve, ApprovalParams(approval.approvalId, ApprovalDecision.AllowOnce)))
                val changes = streamed.await()
                val live = changes.filterIsInstance<SessionChange.Entry>().map { it.entry }

                assertEquals(sent.runId, changes.filterIsInstance<SessionChange.Event>().map { it.event }.filterIsInstance<AgentEvent.RunStarted>().single().runId)
                assertTrue(live.any { it is SessionEntry.ToolResult && !it.isError }, "$live")
                assertEquals("done", live.filterIsInstance<SessionEntry.AssistantMessage>().last().text)
                assertTrue(SessionStatus.Waiting in statuses.await())
                assertTrue(File(home, "agents/default/skills/x/SKILL.md").isFile)
                val caught = client.catchUp(sessionId)
                assertEquals(live, caught.entries.takeLast(live.size))
                assertEquals(LiveState(), caught.live)
                val page = client.call(Api.Sessions, SessionsParams("default"))
                assertEquals(SessionInfo(sessionId, "default", "make a skill", answered = true), page.sessions.single())
                client.close()

                // A paired device reconnects with its token, no code needed
                val again = KinuvoClient(url, "test-phone", token = saved)
                again.connect()
                assertEquals(1, again.call(Api.Sessions, SessionsParams("default")).sessions.size)
                again.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun reconnectSeesPendingApprovalAndCanApproveOrStop() = runBlocking {
        val server = HarnessServer(Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))), modelFactory = { model }))
        val port = server.start("127.0.0.1", 0)
        val url = "ws://127.0.0.1:$port/ws"
        try {
            withTimeout(30_000) {
                val token = server.auth.sessionToken()
                val original = KinuvoClient(url, "phone", token = token).also { it.connect() }
                val first = original.newSession()
                original.catchUp(first)
                val firstRequest = async(start = CoroutineStart.UNDISPATCHED) { original.approvals().first() }
                original.call(Api.Send, SendParams("default", first, "make skill"))
                val approval = firstRequest.await()
                assertTrue(runCatching { original.call(Api.Send, SendParams("default", first, "again")) }.isFailure, "one run per Session")
                original.close()

                val reconnected = KinuvoClient(url, "phone", token = token).also { it.connect() }
                val (_, entries, live) = reconnected.catchUp(first)
                assertEquals(LiveState(running = true, approval = approval), live)
                assertTrue(SessionEntry.UserMessage("make skill") in entries)
                assertEquals("c1", (entries.last() as SessionEntry.ToolCall).id)
                assertEquals(listOf(entries.last()), reconnected.catchUp(first, after = entries.size - 1).entries)
                assertEquals(SessionStatus.Waiting, reconnected.call(Api.Sessions, SessionsParams("default")).sessions.single { it.id == first }.status)
                val idle = async(start = CoroutineStart.UNDISPATCHED) { reconnected.statuses(first).first { it == SessionStatus.Idle } }
                assertTrue(reconnected.call(Api.Approve, ApprovalParams(approval.approvalId, ApprovalDecision.AllowOnce)))
                idle.await()
                assertEquals(LiveState(), reconnected.catchUp(first).live)

                val second = reconnected.newSession()
                reconnected.catchUp(second)
                val secondRequest = async(start = CoroutineStart.UNDISPATCHED) { reconnected.approvals().first() }
                reconnected.call(Api.Send, SendParams("default", second, "stop me"))
                val secondApproval = secondRequest.await()
                assertTrue(secondApproval.approvalId != approval.approvalId)
                reconnected.close()

                val afterStop = KinuvoClient(url, "phone", token = token).also { it.connect() }
                assertEquals(secondApproval, afterStop.catchUp(second).live.approval)
                val stopped = async(start = CoroutineStart.UNDISPATCHED) { afterStop.statuses(second).first { it == SessionStatus.Idle } }
                afterStop.call(Api.Stop, StopParams(second))
                stopped.await()
                val (_, afterEntries, afterLive) = afterStop.catchUp(second)
                assertEquals(LiveState(), afterLive)
                assertEquals("Denied (stopped)", afterEntries.filterIsInstance<SessionEntry.ToolResult>().single().output)
                afterStop.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun listsAgentSkills() = runBlocking {
        File(home, "skills/hidden").apply { mkdirs() }.resolve("SKILL.md")
            .writeText("---\nname: hidden\ndescription: model only\nuser-invocable: false\n---\nbody")
        File(home, "skills/review").apply { mkdirs() }.resolve("SKILL.md").writeText("---\nname: review\ndescription: Reviews code\n---\nbody")
        val server = HarnessServer(Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))), modelFactory = { model }))
        val port = server.start("127.0.0.1", 0)
        try {
            withTimeout(30_000) {
                val client = KinuvoClient("ws://127.0.0.1:$port/ws", "phone", token = server.auth.sessionToken()).also { it.connect() }
                assertEquals(
                    listOf(SkillInfo("hidden", "model only", userInvocable = false, scope = ConfigScope.Shared), SkillInfo("review", "Reviews code", scope = ConfigScope.Shared)),
                    client.call(Api.Skills, AgentRef("default")).take(2),
                )
                client.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun onlySubscribersGetSessionUpdates() = runBlocking {
        val server = HarnessServer(Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))), modelFactory = { model }))
        val port = server.start("127.0.0.1", 0)
        val url = "ws://127.0.0.1:$port/ws"
        try {
            withTimeout(30_000) {
                val phone = KinuvoClient(url, "phone", token = server.auth.sessionToken()).also { it.connect() }
                val desktop = KinuvoClient(url, "desktop", token = server.auth.sessionToken()).also { it.connect() }
                val created = async(start = CoroutineStart.UNDISPATCHED) { phone.events(Api.SessionChanged).first() }
                val sessionId = desktop.newSession()
                assertEquals(sessionId, created.await().id)

                val phoneUpdates = async(start = CoroutineStart.UNDISPATCHED) { phone.events(Api.Update).first() }
                phone.catchUp(sessionId)
                phone.call(Api.Unsubscribe, UnsubscribeParams(sessionId))
                val phoneUpdatesAfter = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(1_000) { phone.events(Api.Update).first() } }
                desktop.catchUp(sessionId)
                val seen = async(start = CoroutineStart.UNDISPATCHED) {
                    desktop.events(Api.Update).mapNotNull { (it.change as? SessionChange.Entry)?.entry as? SessionEntry.UserMessage }.first()
                }
                desktop.call(Api.Send, SendParams("default", sessionId, "hello"))
                assertEquals(SessionEntry.UserMessage("hello"), seen.await())
                assertEquals(sessionId, phoneUpdates.await().sessionId)
                assertEquals(null, phoneUpdatesAfter.await(), "unsubscribed")
                phone.close()
                desktop.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun pagesSessionsAndEntries() = runBlocking {
        val harness = Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))), modelFactory = { model })
        val server = HarnessServer(harness)
        val port = server.start("127.0.0.1", 0)
        try {
            withTimeout(30_000) {
                val client = KinuvoClient("ws://127.0.0.1:$port/ws", "phone", token = server.auth.sessionToken()).also { it.connect() }
                val agent = harness.store.agents().single()
                val ids = List(3) { harness.store.createSession(agent, "i").id.also { Thread.sleep(1_100) } }
                val firstPage = client.call(Api.Sessions, SessionsParams("default", limit = 2))
                assertEquals(ids.reversed().take(2) to true, firstPage.sessions.map { it.id } to firstPage.hasMore)
                val secondPage = client.call(Api.Sessions, SessionsParams("default", before = firstPage.sessions.last().id, limit = 2))
                assertEquals(listOf(ids.first()) to false, secondPage.sessions.map { it.id } to secondPage.hasMore)

                assertFailsWith<IllegalStateException> {
                    client.call(Api.Send, SendParams("default", "missing", "hello"))
                }

                val sessionId = ids.last()
                repeat(5) { harness.store.append(agent, sessionId, SessionEntry.UserMessage("m$it")) }
                val window = client.catchUp(sessionId, after = null, limit = 2)
                assertEquals(4, window.reset)
                assertEquals(listOf("m3", "m4"), window.entries.map { (it as SessionEntry.UserMessage).text })
                val older = client.call(Api.History, HistoryParams("default", sessionId, before = 4, limit = 2))
                assertEquals(listOf(2 to "m1", 3 to "m2"), older.map { it.seq to (it.entry as SessionEntry.UserMessage).text })
                client.close()
            }
        } finally {
            server.stop()
        }
    }
}
