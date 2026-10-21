package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.board.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedServerTest {
    private val serverJobs = mutableListOf<Job>()
    private val stores = mutableListOf<ViewModelStore>()
    private fun store() = ViewModelStore().also(stores::add)
    @Test fun lateBoardLoadsAndErrorsCannotReplaceTheNewServer() = withOwner { owner, _, _ ->
        for (error in listOf(false, true)) {
            val a = Script("A")
            connect(owner, a)
            a.hold(Api.Tasks.name, error)
            val old = owner.board.load()
            a.blocked.receive()
            val b = Script("B")
            connect(owner, b)
            owner.board.load().join()
            val expected = owner.board.state.value
            a.release.complete(Unit)
            old.join()
            assertEquals(expected, owner.board.state.value)
            assertEquals("B", expected.tasks?.single()?.title)
            assertNull(expected.error)
            assertTrue(old.isCancelled)
        }
    }

    @Test fun latePaginationAndHistoryCannotAlterSharedIdsOrBlockNewPages() = withOwner { owner, _, _ ->
        val a = Script("A")
        connect(owner, a)
        owner.sessions.loadSessions("agent").join()
        owner.sessions.view("agent", "session").join()
        owner.sessions.state.first { it.sessions["session"]?.loaded == true }
        a.hold(Api.Sessions.name)
        a.hold(Api.History.name)
        val page = owner.sessions.loadSessions("agent", more = true)
        val history = owner.sessions.loadOlder("session")
        repeat(2) { a.blocked.receive() }
        val b = Script("B")
        connect(owner, b)
        owner.sessions.loadSessions("agent").join()
        owner.sessions.view("agent", "session").join()
        owner.sessions.state.first { it.sessions["session"]?.loaded == true }
        val expected = owner.sessions.state.value
        a.release.complete(Unit)
        page.join(); history.join()
        assertEquals(expected, owner.sessions.state.value)
        assertFalse(owner.sessions.state.value.sessions.getValue("session").loadingOlder)
        owner.sessions.loadSessions("agent", more = true).join()
        assertEquals(2, b.calls.count { it == Api.Sessions.name })
        assertEquals("B", owner.sessions.state.value.lists.getValue("agent").sessions.single().title)
    }

    @Test fun pendingCreateAndUpdateCannotPublishOrTurnCancellationIntoUiErrors() = withOwner { owner, _, _ ->
        for (method in listOf(Api.CreateTask.name, Api.UpdateTask.name)) {
            for (error in listOf(false, true)) {
                val a = Script("A")
                connect(owner, a)
                owner.board.load().join()
                val store = store()
                val vm = BoardViewModel(owner.board)
                store.put("board", vm)
                vm.adding(true)
                a.hold(method, error)
                if (method == Api.CreateTask.name) vm.add(CreateTaskParams("new"))
                else vm.update(UpdateTaskParams("task", title = "edited"))
                a.blocked.receive()
                val b = Script("B")
                connect(owner, b)
                owner.board.load().join()
                val expected = owner.board.state.value
                a.release.complete(Unit)
                a.finished.await()
                yield()
                assertEquals(expected, owner.board.state.value)
                assertEquals(emptyList(), vm.state.value.errors)
                assertTrue(vm.state.value.adding)
                store.clearAndJoin()
            }
        }
    }

    @Test fun pendingSessionCreationCannotSendToEitherServerAfterSwitching() = withOwner { owner, _, _ ->
        val a = Script("A")
        connect(owner, a)
        a.hold(Api.CreateSession.name)
        val send = CoroutineScope(currentCoroutineContext()).async {
            owner.run {
                val id = owner.sessions.createSession("agent").id
                owner.sessions.send("agent", id, "hello")
            }
        }
        a.blocked.receive()
        val b = Script("B")
        connect(owner, b)
        a.release.complete(Unit)
        send.join()
        assertTrue(send.isCancelled)
        assertFalse(Api.Send.name in a.calls)
        assertFalse(Api.Send.name in b.calls)
    }

    @Test fun pendingConfigMutationsCannotEditOrRequestFollowupsOnTheNewServer() = withOwner { owner, _, _ ->
        for (method in listOf(Api.UpdateAgent.name, Api.SaveSkill.name)) {
            val a = Script("A")
            connect(owner, a)
            a.hold(method)
            val mutation = CoroutineScope(currentCoroutineContext()).async {
                if (method == Api.UpdateAgent.name) owner.config.updateAgent(UpdateAgentParams("agent", name = "changed"))
                else owner.config.saveSkill(SaveSkillParams("agent", "skill", "body", ConfigScope.Agent))
            }
            a.blocked.receive()
            val b = Script("B")
            connect(owner, b)
            owner.config.load("agent").join()
            val expected = owner.config.state.value
            val skills = b.calls.count { it == Api.Skills.name }
            a.release.complete(Unit)
            mutation.join()
            assertTrue(mutation.isCancelled)
            assertEquals(expected, owner.config.state.value)
            assertEquals(skills, b.calls.count { it == Api.Skills.name })
        }
    }

    @Test fun lateOpenAndConnectFailuresCannotReplaceTheNewReadyServer() = withOwner { owner, _, access ->
        for (preparing in listOf(false, true)) {
            val a = Script("A")
            val b = Script("B")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var first = true
            val embedded = object : EmbeddedServer {
                override suspend fun endpoint(): Endpoint {
                    if (!first) return Endpoint("local:test", "token", { b })
                    first = false
                    if (preparing) withContext(NonCancellable) { entered.complete(Unit); release.await() }
                    return Endpoint("local:test", "token", { a })
                }
            }
            val prefs = MemoryPreferences()
            val servers = Servers(prefs, embedded, prefs.credentials)
            val store = store()
            val app = AppViewModel(servers, Device("test"), owner, access)
            store.put("app", app)
            if (!preparing) a.hold(Api.Connect.name, error = true)
            app.use(Server.Embedded)
            if (preparing) entered.await() else a.blocked.receive()
            app.use(Server.Embedded)
            app.state.first { (it.connection as? Connection.Ready)?.client?.lastConnect?.serverName == "B" }
            val ready = app.state.value.connection
            if (preparing) release.complete(Unit) else { a.release.complete(Unit); a.finished.await() }
            yield()
            assertEquals(ready, app.state.value.connection)
            assertSame((ready as Connection.Ready).client, owner.client)
            assertNull(app.state.value.notice)
            store.clearAndJoin()
        }
    }

    @Test fun directSessionRpcCannotPublishAfterTheScreenServerEnds() = withOwner { owner, _, _ ->
        val a = Script("A")
        connect(owner, a)
        a.hold(Api.Models.name, error = true)
        val store = store()
        val vm = SessionViewModel(requireNotNull(owner.client), owner.sessions, Route.Chat("agent", null))
        store.put("session", vm)
        a.blocked.receive()
        connect(owner, Script("B"))
        val expected = vm.state.value
        a.release.complete(Unit)
        a.finished.await()
        yield()
        assertEquals(expected, vm.state.value)
        assertEquals(emptyList(), vm.state.value.errors)
        store.clearAndJoin()
    }

    @Test fun reconnectCatchesUpCachedSessionsWithoutResettingThem() = withOwner { owner, _, _ ->
        val first = Script("A")
        val second = Script("A")
        val sockets = Channel<Script>(Channel.UNLIMITED)
        sockets.send(first); sockets.send(second)
        val client = KinuvoClient("local:test", "test", connection = { sockets.receive() })
        owner.open(client, "local:test")
        serverJobs += owner.scope.coroutineContext.job
        client.connect()
        owner.activate()
        owner.sessions.loadSessions("agent").join()
        owner.sessions.view("agent", "session").join()
        owner.sessions.state.first { it.sessions["session"]?.loaded == true }
        val data = owner.sessions.state.value.sessions.getValue("session")
        first.disconnect()
        second.caughtUp.await()
        assertEquals(data.seen, second.after)
        assertEquals(data.entries, owner.sessions.state.value.sessions.getValue("session").entries)
        owner.sessions.state.first { it.lists["agent"]?.sessions?.single()?.title == "A" }
    }

    @Test fun closeCancelsPendingWorkCollectorsAndNotificationsAndClearsCaches() = withOwner { owner, notices, _ ->
        val a = Script("A")
        connect(owner, a)
        owner.board.load().join()
        a.hold(Api.Tasks.name, error = true)
        val load = owner.board.load()
        a.blocked.receive()
        owner.close()
        a.release.complete(Unit)
        load.join()
        a.event(Api.TaskChanged, TaskInfo("task", "list", "List", "late", status = TaskStatus.Waiting))
        a.event(Api.SessionChanged, SessionInfo("session", "agent", "late", SessionStatus.Waiting))
        yield()
        assertEquals(BoardData(), owner.board.state.value)
        assertEquals(RepositoryState(), owner.sessions.state.value)
        assertEquals(emptyMap(), owner.config.state.value)
        assertEquals(DevicesState(), owner.devices.state.value)
        assertNull(owner.knowledge.changes.value)
        assertEquals(emptyList(), notices)
        assertTrue(a.closed.await())
    }

    @Test fun nativeBoardUsesTheSameRepositoryAndDisposesAndRecreatesItsStateObserver() = withOwner { owner, _, _ ->
        val script = Script("native")
        connect(owner, script)
        val mainThread = Thread.currentThread()
        val destination = BoardDestination(owner.board)
        val states = mutableListOf<BoardState>()
        destination.observe { assertSame(mainThread, Thread.currentThread()); states += it }
        destination.viewModel.state.first { it.board.tasks != null }
        destination.viewModel.adding(true)
        destination.viewModel.add(CreateTaskParams("new"))
        destination.viewModel.state.first { !it.adding && it.board.tasks?.any { task -> task.id == "new" } == true }
        destination.viewModel.showDone(true)
        assertTrue(destination.state.showDone)
        val destinationJob = destination.viewModel.viewModelScope.coroutineContext.job
        destination.close()
        destinationJob.join()
        val count = states.size
        script.event(Api.TaskChanged, TaskInfo("task", "list", "List", "changed", status = TaskStatus.Waiting))
        owner.board.state.first { it.tasks?.firstOrNull { task -> task.id == "task" }?.status == TaskStatus.Waiting }
        assertEquals(count, states.size)
        val recreated = BoardDestination(owner.board)
        recreated.viewModel.state.first { it.board.tasks != null }
        assertFalse(recreated.state.showDone)
        assertEquals(owner.board.state.value, recreated.state.board)
        val recreatedJob = recreated.viewModel.viewModelScope.coroutineContext.job
        recreated.close()
        recreatedJob.join()
    }

    @Test fun olderAgentResolutionCannotOverrideQuickChatSelectionOrBack() = withOwner { owner, _, access ->
        val script = Script("selection")
        connect(owner, script)
        val prefs = MemoryPreferences()
        val store = store()
        val app = AppViewModel(Servers(prefs, credentials = prefs.credentials), Device("test"), owner, access)
        store.put("app", app)
        try {
            for (id in listOf("A", "B", "C")) owner.sessions.loadSessions(id).join()
            yield()
            val a = Route.Chat("A", compose = true)
            app.showView(a)
            var current: Route = a
            val open: (Route.Chat) -> Unit = { current = it; app.showView(it) }
            script.hold(Api.Sessions.name)
            app.selectAgent("B", current, compact = false, open)
            script.blocked.receive()
            assertNull(app.resolveView(a, a.copy(sessionId = "old")))
            assertNull(app.resolveView(Route.Chat(), a))
            app.selectAgent("C", current, compact = true, open)
            val c = current
            script.release.complete(Unit)
            script.finished.await()
            yield()
            assertEquals(c, current)
            assertEquals("C", app.state.value.agentId)
            app.showView(a)
            assertEquals("A", app.state.value.agentId)
            assertEquals(a, app.lastView("A"))
        } finally { store.clearAndJoin() }
    }

    @Test fun quickSendKeepsItsAgentWhenSelectionChangesDuringCreation() = withOwner { owner, _, access ->
        val script = Script("send")
        connect(owner, script)
        val prefs = MemoryPreferences()
        val store = store()
        val app = AppViewModel(Servers(prefs, credentials = prefs.credentials), Device("test"), owner, access)
        store.put("app", app)
        try {
            val a = Route.Chat("A")
            app.showView(a)
            script.hold(Api.CreateSession.name)
            val sending = CoroutineScope(currentCoroutineContext()).async { app.quickSend("A", "hello") }
            script.blocked.receive()
            app.selectAgent("B", a, compact = true) { app.showView(it) }
            script.release.complete(Unit)
            assertEquals("new", sending.await())
            val sent = script.requests.single { it.method == Api.Send.name }
            val params = ProtocolJson.decodeFromJsonElement(Api.Send.params, sent.params)
            assertEquals("A", params.agentId)
            assertEquals("new", params.sessionId)
            assertEquals("B", app.state.value.agentId)
        } finally { store.clearAndJoin() }
    }

    @Test fun roomsShareServerLifetimeAndSendStructuredRecipients() = withOwner { owner, _, _ ->
        val script = Script("rooms")
        connect(owner, script)
        val destination = store()
        val vm = kinuvo.app.rooms.RoomsViewModel(owner.rooms)
        destination.put("rooms", vm)
        val room = vm.state.first { it.rooms.isNotEmpty() }.rooms.single()
        vm.select(room)
        vm.state.first { it.view != null }
        vm.recipient("outsider")
        assertEquals(emptyList(), vm.state.value.recipients)
        vm.recipient("other")
        vm.text("Check this")
        vm.send().join()
        val request = script.requests.single { it.method == Api.SendRoom.name }
        val params = ProtocolJson.decodeFromJsonElement(Api.SendRoom.params, request.params)
        assertEquals(listOf("other"), params.recipients)
        assertEquals("room-session", params.sessionId)
        assertEquals("Check this", params.text)
        assertEquals("", vm.state.value.text)
        assertEquals(emptyList(), vm.state.value.recipients)
        val job = vm.viewModelScope.coroutineContext.job
        connect(owner, Script("new-server"))
        job.join()
        assertTrue(job.isCancelled)
        destination.clearAndJoin()
    }

    private fun withOwner(block: suspend (ConnectedServer, MutableList<Notice>, DeviceAccess) -> Unit) = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Dispatchers.setMain(main)
            try {
                withContext(main) {
                    withTimeout(15_000) {
                        val prefs = MemoryPreferences()
                        val reader = object : DeviceReader {
                            override val kinds = emptyList<String>()
                            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
                            override fun read(requestJson: String, completion: (String) -> Unit) = error("Unused")
                        }
                        val notices = mutableListOf<Notice>()
                        val access = DeviceAccess(prefs, reader)
                        val owner = ConnectedServer(Notifications(listOf(Notifier { notices += it })), access)
                        try { block(owner, notices, access) } finally {
                            stores.forEach { it.clearAndJoin() }
                            serverJobs += owner.scope.coroutineContext.job
                            owner.close()
                            serverJobs.forEach { it.join() }
                        }
                    }
                }
            } finally { Dispatchers.resetMain() }
        }
    }

    private suspend fun connect(owner: ConnectedServer, script: Script) {
        val client = KinuvoClient("local:test", "test", connection = { script })
        owner.open(client, "local:test")
        serverJobs += owner.scope.coroutineContext.job
        client.connect()
        owner.activate()
    }

    private class Script(private val name: String) : FrameConnection {
        private val frames = Channel<Frame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        private val held = ConcurrentHashMap<String, Boolean>()
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val requests = java.util.concurrent.CopyOnWriteArrayList<Frame.Request>()
        val blocked = Channel<Frame.Request>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Boolean>()
        val caughtUp = CompletableDeferred<Unit>()
        var after: Int? = null
        fun hold(method: String, error: Boolean = false) { held[method] = error }
        fun disconnect() { frames.close() }

        override suspend fun send(frame: Frame) {
            val request = frame as Frame.Request
            calls += request.method
            requests += request
            held[request.method]?.let { error ->
                blocked.send(request)
                try {
                    withContext(NonCancellable) {
                        release.await()
                        if (error) error("Late A error")
                        respond(request)
                    }
                } finally { finished.complete(Unit) }
                return
            }
            respond(request)
        }

        private suspend fun respond(request: Frame.Request) {
            when (request.method) {
                Api.Connect.name -> reply(request, Api.Connect, ConnectResult("token", name, capabilities = ServerCapabilities("test"), management = true))
                Api.Presence.name -> reply(request, Api.Presence, Unit)
                Api.Agents.name -> reply(request, Api.Agents, listOf(AgentInfo("agent", name, "none")))
                Api.Rooms.name -> reply(request, Api.Rooms, listOf(Room("team", name, listOf("agent", "other"), "agent")))
                Api.RoomSessions.name -> reply(request, Api.RoomSessions, listOf(RoomSession("room-session", "team")))
                Api.RoomSession.name -> reply(request, Api.RoomSession, RoomView(RoomSession("room-session", "team")))
                Api.SendRoom.name -> reply(request, Api.SendRoom, Unit)
                Api.Models.name -> reply(request, Api.Models, ModelsCatalog())
                Api.Providers.name -> reply(request, Api.Providers, emptyList())
                Api.TaskLists.name -> reply(request, Api.TaskLists, listOf(TaskListInfo("list", "List")))
                Api.Tasks.name -> reply(request, Api.Tasks, listOf(TaskInfo("task", "list", "List", name)))
                Api.CreateTask.name -> reply(request, Api.CreateTask, TaskInfo("new", "list", "List", name))
                Api.UpdateTask.name -> reply(request, Api.UpdateTask, TaskInfo("task", "list", "List", name))
                Api.Sessions.name -> reply(request, Api.Sessions, SessionPage(listOf(SessionInfo("session", "agent", name)), true))
                Api.Send.name -> reply(request, Api.Send, SendResult("new", "run", "message"))
                Api.CreateSession.name -> reply(request, Api.CreateSession, SessionInfo("new", "agent", name))
                Api.Agent.name -> reply(request, Api.Agent, details())
                Api.UpdateAgent.name -> reply(request, Api.UpdateAgent, details())
                Api.SaveSkill.name -> reply(request, Api.SaveSkill, SkillInfo("skill", "", scope = ConfigScope.Agent))
                Api.Skills.name -> reply(request, Api.Skills, emptyList())
                Api.McpServers.name -> reply(request, Api.McpServers, emptyList())
                Api.History.name -> reply(request, Api.History, listOf(SessionChange.Entry(19, SessionEntry.UserMessage(name))))
                Api.CatchUp.name -> {
                    val params = ProtocolJson.decodeFromJsonElement(Api.CatchUp.params, request.params)
                    after = params.after
                    if (params.after == null) {
                        event(Api.Update, SessionUpdate("agent", "session", SessionChange.Reset(20)))
                        event(Api.Update, SessionUpdate("agent", "session", SessionChange.Entry(20, SessionEntry.UserMessage(name))))
                    }
                    event(Api.Update, SessionUpdate("agent", "session", SessionChange.Live(LiveState())))
                    reply(request, Api.CatchUp, Unit)
                    caughtUp.complete(Unit)
                }
                else -> frames.send(Frame.Response(request.id, false, error = "Unsupported in test: ${request.method}"))
            }
        }

        private fun details() = AgentDetails(AgentInfo("agent", name, "none"), AgentSettings("model", "medium", "default", "none"), "", "")
        private suspend fun <P, R> reply(request: Frame.Request, method: Method<P, R>, result: R) {
            frames.send(Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(method.result, result)))
        }
        suspend fun <T> event(type: EventType<T>, payload: T) {
            frames.send(Frame.Event(type.name, ProtocolJson.encodeToJsonElement(type.payload, payload), 0))
        }
        override suspend fun close() { closed.complete(true) }
    }
}
