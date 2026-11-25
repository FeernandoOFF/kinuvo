package kinuvo.server

import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.server.store.FileStore
import kinuvo.server.store.Store
import kinuvo.protocol.session.QueuedMessage
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.SessionChange
import kinuvo.protocol.agui.AgUiEvent
import kinuvo.protocol.agui.RunOutcome
import kinuvo.protocol.session.withWrittenAt
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionUpdate
import kinuvo.protocol.ProtocolJson
import kinuvo.server.execution.executionSnapshot
import kinuvo.server.store.SessionCommitConflict
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kinuvo.protocol.session.SessionSettings
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.SessionParent
import kinuvo.protocol.session.ToolCallId
import kinuvo.protocol.session.RunId
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.routine.RoutineRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kinuvo.protocol.session.SessionStatus
import kinuvo.server.plugin.appendText
import kinuvo.server.store.CorruptSession
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SessionLogTest {
    @Test fun deletingParentRescansForChildrenCreatedWhileStopping() = runBlocking {
        val fixture = RuntimeFixture()
        val log = SessionLog(fixture.store)
        val agent = fixture.agent.id
        val parent = log.createSession(agent, "")
        val deleting = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        log.onDelete = { _, id ->
            if (id == parent.id) {
                deleting.complete(Unit)
                stopped.await()
            }
        }
        val deletion = async { log.deleteSession(agent, parent.id) }
        withTimeout(1000) { deleting.await() }
        log.createChild(agent, SessionParent(agent, parent.id, ToolCallId("concurrent"), 1))
        stopped.complete(Unit)
        withTimeout(1000) { deletion.await() }
        assertTrue(log.sessions(agent).isEmpty())
    }

    @Test fun inboxDetailsSurviveReopeningTheStore() = runBlocking {
        val home = TestHome()
        val agent = home.agent.id
        val log = SessionLog(home.store)
        val id = log.createSession(agent, "").id
        log.updateDetails(agent, id, title = "Saved", favorite = true, settled = true)
        assertTrue(SessionLog(FileStore(home.files)).page(agent, null, 10).sessions.single().settled)

        val run = RunId("run")
        log.publish(agent, id, AgentEvent.RunStarted(run))
        assertFalse(log.session(agent, id).settled)
        log.updateDetails(agent, id, settled = true)
        val interaction = PendingInteraction.Unsupported(InteractionId("question"), "Waiting")
        log.publish(agent, id, AgentEvent.InteractionRequested(interaction))
        assertFalse(log.session(agent, id).settled)
        log.updateDetails(agent, id, settled = true)
        log.append(agent, id, SessionEntry.AssistantMessage("Finished"))
        log.publish(agent, id, AgentEvent.RunFinished(run))

        val reopened = SessionLog(FileStore(home.files))
        val unread = reopened.page(agent, null, 10).sessions.single()
        assertTrue(unread.unread)
        assertTrue(unread.settled)
        assertTrue(unread.favorite)
        assertEquals("Saved", unread.title)
        reopened.updateDetails(agent, id, seen = true)
        assertFalse(SessionLog(FileStore(home.files)).page(agent, null, 10).sessions.single().unread)
    }

    @Test fun onlyReportingRoutineRunsMarkTheSessionUnread() = runBlocking {
        val log = SessionLog(RuntimeFixture().store)
        val agent = AgentId("test")
        val routine = RoutineRef("routine", "Routine")
        val id = log.createSession(agent, "", routine).id
        for (reports in listOf(true, false)) {
            val run = RunId("run-$reports")
            log.publish(agent, id, AgentEvent.RunStarted(run))
            log.append(agent, id, SessionEntry.UserMessage("Run", routine))
            log.append(agent, id, SessionEntry.AssistantMessage("Interim text"))
            log.append(agent, id, SessionEntry.Outcome(reports, ""))
            log.publish(agent, id, AgentEvent.RunFinished(run))
            assertEquals(reports, log.page(agent, null, 10, routine.id).sessions.single().unread)
            log.updateDetails(agent, id, seen = true)
        }
    }

    @Test fun firstChatPageCountsSettledSessionsAcrossPages() = runBlocking {
        val log = SessionLog(RuntimeFixture().store)
        val agent = AgentId("test")
        val routine = RoutineRef("routine", "Routine")
        repeat(2) {
            val id = log.createSession(agent, "").id
            log.updateDetails(agent, id, settled = true)
        }
        val routineOnly = log.createSession(agent, "", routine).id
        log.updateDetails(agent, routineOnly, settled = true)
        val hidden = log.createSession(agent, "").id
        log.append(agent, hidden, SessionEntry.Details(hidden = true, settled = true))
        val favorite = log.createSession(agent, "").id
        log.updateDetails(agent, favorite, settled = true)
        log.updateDetails(agent, favorite, favorite = true)

        val first = log.page(agent, null, 1)
        assertEquals(2, first.settledCount)
        assertTrue(first.favorites.single().settled)
        assertTrue(first.hasMore)
        assertEquals(0, log.page(agent, first.sessions.single().id.value, 1).settledCount)
        assertEquals(2, log.page(agent, null, 1, routine.id).settledCount)
    }

    @Test fun aPageLargerThanOneFrameArrivesAsASnapshotThenEntries() = runBlocking {
        val fixture = RuntimeFixture()
        val log = SessionLog(fixture.store)
        val id = log.createSession(fixture.agent.id, "").id
        val texts = List(8) { i -> "$i".repeat(2 * 1024 * 1024) }
        texts.forEach { log.append(fixture.agent.id, id, SessionEntry.UserMessage(it)) }
        val received = mutableListOf<SessionChange>()
        log.subscribe(fixture.agent.id, id, null, 100, object : SessionLog.Subscriber {
            override fun offer(update: SessionUpdate): Boolean { received += update.change; return true }
        })
        val snapshot = (received.first() as SessionChange.Snapshot).snapshot
        val rest = received.drop(1).map { it as SessionChange.Entry }
        assertTrue(snapshot.entries.isNotEmpty() && rest.isNotEmpty())
        assertEquals((snapshot.firstSeq + snapshot.entries.size until snapshot.seq).toList(), rest.map { it.seq })
        assertEquals(texts, (snapshot.entries + rest.map { it.entry }).filterIsInstance<SessionEntry.UserMessage>().map { it.text })
        received.forEach { assertTrue(ProtocolJson.encodeToString(SessionChange.serializer(), it).length < 15 * 1024 * 1024) }
    }

    @Test fun anExecutionSnapshotKeepsEntriesPastTheInlineBudget() = runBlocking {
        val fixture = RuntimeFixture()
        val log = SessionLog(fixture.store)
        val id = log.createSession(fixture.agent.id, "").id
        val texts = List(8) { i -> "$i".repeat(2 * 1024 * 1024) }
        texts.forEach { log.append(fixture.agent.id, id, SessionEntry.UserMessage(it)) }
        assertEquals(texts, log.executionSnapshot(fixture.agent.id, id).messages.map { it.text })
    }

    @Test fun queuedMessagesReachLiveSubscribersAndReconnectWhileIdle() = runBlocking {
        val fixture = RuntimeFixture()
        val log = SessionLog(fixture.store)
        val id = log.createSession(fixture.agent.id, "").id
        val queue = listOf(QueuedMessage("q", "waiting"))
        log.publish(fixture.agent.id, id, AgentEvent.QueueChanged(queue))
        log.append(fixture.agent.id, id, SessionEntry.UserMessage("previous"))
        val received = mutableListOf<SessionUpdate>()
        log.subscribe(fixture.agent.id, id, null, 100, object : SessionLog.Subscriber {
            override fun offer(update: SessionUpdate): Boolean { received += update; return true }
        })
        assertEquals(queue, received.map { it.change }.filterIsInstance<SessionChange.Snapshot>().single().snapshot.state.queued)
        log.publish(fixture.agent.id, id, AgentEvent.RunStarted(RunId("run")))
        log.publish(fixture.agent.id, id, AgentEvent.RunFinished(RunId("run")))
        received.clear()
        log.publish(fixture.agent.id, id, AgentEvent.QueueChanged(emptyList()))
        assertEquals(AgentEvent.QueueChanged(emptyList()), (received.single().change as SessionChange.Item).event)
    }

    @Test fun metadataUpdatesPreserveEachOther() = runBlocking {
        val log = SessionLog(RuntimeFixture().store)
        val id = log.createSession(AgentId("external"), "").id
        log.append(AgentId("external"), id, SessionEntry.UserMessage("Automatic title"))
        log.updateDetails(AgentId("external"), id, "  Saved title  ", true)
        assertEquals("Saved title", log.session(AgentId("external"), id).title)
        assertEquals("x".repeat(MAX_SESSION_TITLE), log.updateDetails(AgentId("external"), id, "x".repeat(120), null).title)
        assertEquals("Automatic title", log.updateDetails(AgentId("external"), id, "   ", null).title)
        assertEquals("Automatic title", log.updateDetails(AgentId("external"), id, null, true).title)
        assertEquals(true, log.updateDetails(AgentId("external"), id, "Renamed", null).favorite)
        val favorite = log.updateDetails(AgentId("external"), id, null, false)
        assertEquals("Renamed", favorite.title)
        assertEquals(false, favorite.favorite)
    }

    @Test
    fun batchPublishesOnceBeforeTerminalAndBrowserCleanupPrecedesRemoval() = runBlocking {
        val log = SessionLog(RuntimeFixture().store) { 123L }
        val session = log.createSession(AgentId("external"), "").id
        val updates = mutableListOf<SessionUpdate>()
        log.subscribe(AgentId("external"), session, null, 100, object : SessionLog.Subscriber {
            override fun offer(update: SessionUpdate): Boolean { updates += update; return true }
        })
        updates.clear()
        log.publish(AgentId("external"), session, AgentEvent.RunStarted(RunId("run")))
        val reply = SessionEntry.AssistantMessage("reply", "reply")
        assertEquals(listOf(reply), log.appendAll(AgentId("external"), session, listOf(reply)).map { it.withWrittenAt(null) })
        assertEquals(emptyList(), log.appendAll(AgentId("external"), session, listOf(reply)).map { it.withWrittenAt(null) })
        log.publish(AgentId("external"), session, AgentEvent.RunFinished(RunId("run")))
        val committed = updates.mapNotNull { (it.change as? SessionChange.Entry)?.entry as? SessionEntry.AssistantMessage }.single()
        assertEquals(123L, committed.writtenAt)
        assertEquals(committed, log.history(AgentId("external"), session, 100, 100).map { it.entry }.filterIsInstance<SessionEntry.AssistantMessage>().single())
        assertEquals(committed, log.entries(AgentId("external"), session).filterIsInstance<SessionEntry.AssistantMessage>().single())
        val entryIndex = updates.indexOfFirst { (it.change as? SessionChange.Entry)?.entry == committed }
        val finishedIndex = updates.indexOfFirst { (it.change as? SessionChange.Run)?.event is AgUiEvent.RunFinished }
        assertTrue(entryIndex in 0 until finishedIndex)
        assertFailsWith<SessionCommitConflict> { log.appendAll(AgentId("external"), session, listOf(reply.copy(text = "conflict"))) }
        var cleaned = false
        log.onDelete = { owner, id ->
            assertEquals(AgentId("external"), owner)
            assertEquals(session, id)
            assertTrue(log.entries(owner, id).isNotEmpty())
            cleaned = true
        }
        log.deleteSession(AgentId("external"), session)
        assertTrue(cleaned)
        assertFalse(log.watched(session))
    }

    @Test
    fun silentCleanupKeepsSettingsAndSerializesConcurrentChanges() = runBlocking {
        withTimeout(10_000) {
            val files = RuntimeFixture().store
            val truncating = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val store = object : Store by files {
                override suspend fun truncate(agentId: AgentId, sessionId: SessionId, size: Int) {
                    truncating.complete(Unit)
                    resume.await()
                    files.truncate(agentId, sessionId, size)
                }
            }
            val log = SessionLog(store)
            val agent = Agent(AgentId("test"), "Test", AgentConfig())
            val session = log.createSession(agent.id, "Instructions").id
            val before = log.entries(agent.id, session)
            val first = SessionEntry.Settings(SessionSettings(model = "first"))
            val latest = SessionEntry.Settings(SessionSettings(model = "latest"))
            log.append(agent.id, session, SessionEntry.UserMessage("Check"))
            log.append(agent.id, session, first)
            val cleanup = async { log.discardSilentRun(agent.id, session, before.size) }
            truncating.await()
            val change = async(start = CoroutineStart.UNDISPATCHED) { log.append(agent.id, session, latest) }
            assertFalse(change.isCompleted)
            resume.complete(Unit)
            cleanup.await()
            change.await()
            assertEquals((before + first + latest).map { it.withWrittenAt(null) }, log.entries(agent.id, session).map { it.withWrittenAt(null) })
            assertEquals(latest.settings, log.session(agent.id, session).settings)
        }
    }

    @Test
    fun publishesNeverReadTranscriptsAgainAndKeepPerSessionOrder() = runBlocking {
        val home = TestHome()
        val log = SessionLog(home.store)
        val agent = home.agent.id
        val a = log.createSession(agent, "").id
        val b = log.createSession(agent, "").id
        val seen = mutableMapOf(a to mutableListOf<String>(), b to mutableListOf<String>())
        for (id in listOf(a, b)) log.subscribe(agent, id, null, 100, object : SessionLog.Subscriber {
            override fun offer(update: SessionUpdate): Boolean {
                when (val change = update.change) {
                    is SessionChange.Run -> (change.event as? AgUiEvent.TextMessageContent)?.let { seen.getValue(id) += it.delta }
                    is SessionChange.Item -> (change.event as? AgentEvent.TextMessageContent)?.let { seen.getValue(id) += it.delta }
                    else -> Unit
                }
                return true
            }
        })
        log.publish(agent, a, AgentEvent.RunStarted(RunId("a")))
        val reads = home.store.transcriptReads
        repeat(20) {
            log.publish(agent, a, AgentEvent.TextMessageContent("m", "a$it"))
            log.publish(agent, b, AgentEvent.TextMessageContent("m", "b$it"))
        }
        log.publish(agent, a, AgentEvent.RunFinished(RunId("a")))
        assertEquals(reads, home.store.transcriptReads)
        val deltas = { id: SessionId -> seen.getValue(id) }
        assertEquals(List(20) { "a$it" }, deltas(a))
        assertEquals(List(20) { "b$it" }, deltas(b))
        assertEquals(SessionStatus.Idle, log.status(a))
    }

    @Test
    fun publishToAnotherAgentsSessionFailsAndCorruptionStillSurfaces() = runBlocking {
        val home = TestHome()
        val log = SessionLog(home.store)
        val other = home.store.createAgent("other")
        val id = log.createSession(home.agent.id, "").id
        assertFailsWith<IllegalArgumentException> { log.publish(other.id, id, AgentEvent.RunStarted(RunId("x"))) }
        log.publish(home.agent.id, id, AgentEvent.RunStarted(RunId("x")))
        Path(home.files.sessions(home.agent.id), "$id.jsonl").appendText("{\"type\":")
        assertFailsWith<CorruptSession> { log.publish(home.agent.id, id, AgentEvent.RunFinished(RunId("x"))) }
        Unit
    }

    @Test
    fun pageDoesNotReadEveryTranscriptAgain() = runBlocking {
        val home = TestHome()
        val log = SessionLog(home.store)
        repeat(30) { log.createSession(home.agent.id, "s$it") }
        log.page(home.agent.id, null, 20)
        val reads = home.store.transcriptReads
        assertEquals(20, log.page(home.agent.id, null, 20).sessions.size)
        assertEquals(reads, home.store.transcriptReads)
    }

    @Test
    fun runErrorsAndWarningsAreOrderedEntriesThatSurviveAReconnect() = runBlocking {
        val home = TestHome()
        val agent = home.agent.id
        val log = SessionLog(home.store)
        val id = log.createSession(agent, "").id
        log.append(agent, id, SessionEntry.UserMessage("first"))
        log.publish(agent, id, AgentEvent.RunStarted(RunId("run")))
        log.publish(agent, id, AgentEvent.Warning("MCP server 'Picsart' needs sign-in"))
        log.publish(agent, id, AgentEvent.RunError(RunId("run"), "Codex request failed (200)"))
        // The next turn's own message must land after the previous turn's errors, not get stuck behind them.
        log.append(agent, id, SessionEntry.UserMessage("second"))

        val entries = log.entries(agent, id)
        val codes = entries.filterIsInstance<SessionEntry.UserMessage>().map { it.text } +
            entries.filterIsInstance<SessionEntry.Notice>().map { it.code }
        assertEquals(listOf("first", "run_error", "warning", "second").sorted(), codes.sorted())
        assertEquals(listOf("warning", "run_error"), entries.filterIsInstance<SessionEntry.Notice>().map { it.code })
        assertTrue(entries.indexOfFirst { it is SessionEntry.UserMessage && it.text == "first" } <
            entries.indexOfFirst { it is SessionEntry.Notice })
        assertTrue(entries.indexOfLast { it is SessionEntry.Notice } <
            entries.indexOfFirst { it is SessionEntry.UserMessage && it.text == "second" })

        // A reconnect subscribes fresh, from a Server that may even have restarted; the entries must still be there.
        val reopened = SessionLog(FileStore(home.files))
        assertEquals(entries.map { it.withWrittenAt(null) }, reopened.entries(agent, id).map { it.withWrittenAt(null) })
    }

    @Test
    fun stoppingARunClearsRunningAndAnyPendingInteractionItLeftBehind() = runBlocking {
        val log = SessionLog(RuntimeFixture().store)
        val agent = AgentId("test")
        val id = log.createSession(agent, "").id
        log.publish(agent, id, AgentEvent.RunStarted(RunId("run")))
        val interaction = PendingInteraction.Unsupported(InteractionId("connect"), "Needs sign-in")
        log.publish(agent, id, AgentEvent.InteractionRequested(interaction))
        assertEquals(SessionStatus.Waiting, log.status(id))

        val updates = mutableListOf<SessionUpdate>()
        log.subscribe(agent, id, 0, 100, object : SessionLog.Subscriber {
            override fun offer(update: SessionUpdate): Boolean { updates += update; return true }
        })
        updates.clear()

        // The run is cancelled rather than finishing or erroring, so no terminal event of its own ever arrives.
        log.clearIfStopped(agent, id)

        assertEquals(SessionStatus.Idle, log.status(id))
        assertEquals(listOf(AgentEvent.InteractionResolved(interaction.id), AgentEvent.Idle), updates.map { it.change }.filterIsInstance<SessionChange.Item>().map { it.event })
        assertTrue(updates.map { it.change }.filterIsInstance<SessionChange.Run>().all { (it.event as? AgUiEvent.RunFinished)?.outcome == RunOutcome.Cancelled })

        // Already-resolved (Idle) sessions are left alone: no spurious update for a run that finished normally.
        updates.clear()
        log.clearIfStopped(agent, id)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun recoverOrphanedToolCallsClosesOutCallsLeftRunningByAProcessRestart() = runBlocking {
        val home = TestHome()
        val agent = home.agent.id
        val log = SessionLog(home.store)
        val stuck = log.createSession(agent, "").id
        log.append(agent, stuck, SessionEntry.ToolCall(ToolCallId("mcp_login"), "mcp_login", "{}"))
        val answered = log.createSession(agent, "").id
        log.append(agent, answered, SessionEntry.ToolCall(ToolCallId("c1"), "read", "{}"))
        log.append(agent, answered, SessionEntry.ToolResult(ToolCallId("c1"), "read", "ok"))
        val external = log.createSession(agent, "").id
        log.append(agent, external, SessionEntry.ToolCall(ToolCallId("c2"), "read", "{}"))

        // A fresh process: nothing can still be running, so any call still missing its result is orphaned.
        val restarted = SessionLog(FileStore(home.files))
        restarted.recoverOrphanedToolCalls { it == external }

        val stuckResult = restarted.entries(agent, stuck).filterIsInstance<SessionEntry.ToolResult>().single()
        assertEquals(ToolCallId("mcp_login"), stuckResult.id)
        assertTrue(stuckResult.isError)

        assertEquals(1, restarted.entries(agent, answered).filterIsInstance<SessionEntry.ToolResult>().size)
        assertTrue(restarted.entries(agent, external).filterIsInstance<SessionEntry.ToolResult>().isEmpty())
    }
}
