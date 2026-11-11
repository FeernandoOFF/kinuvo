package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.*

import androidx.lifecycle.ViewModelStore
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ClientCacheTest {
    private val url = "ws://home:8787/ws"
    private val cached = Snapshot(
        syncedAt = 1_000,
        agents = listOf(AgentInfo("agent", "Cached", "none")),
        lists = mapOf("agent" to SessionList(listOf(SessionInfo("session", "agent", "Cached chat")))),
        sessions = mapOf("session" to CachedSession("agent", 0, listOf(SessionEntry.UserMessage("hi"), SessionEntry.AssistantMessage("hello", "m1")))),
    )

    @Test fun filesKeepSnapshotsPerServerAndDropOtherProtocols() = runBlocking {
        val dir = Files.createTempDirectory("client-cache").toFile()
        try {
            val cache = deviceCache(dir.path)
            cache.write(url, cached)
            cache.write("ws://other/ws", cached.copy(syncedAt = 2_000))
            assertEquals(cached, deviceCache(dir.path).read(url))
            assertEquals(2_000, cache.read("ws://other/ws")?.syncedAt)
            cache.write(url, cached.copy(protocol = ProtocolVersion.Current - 1))
            assertNull(cache.read(url))
            cache.clear()
            assertNull(cache.read("ws://other/ws"))
            assertTrue(dir.listFiles().isNullOrEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aCachedServerShowsOfflineThenCatchesUpFromWhatItHolds() = runBlocking {
        val server = FakeServer()
        val attempts = Channel<Unit>(Channel.UNLIMITED)
        withApp(transport = { attempts.send(Unit); if (server.reachable.isCompleted) server else throw java.io.IOException("Unreachable") }) { app, cache ->
            withTimeout(10_000) {
                val offline = app.state.first { it.connection is Connection.Ready }
                assertFalse(offline.online)
                assertEquals(1_000, offline.syncedAt)
                assertEquals(listOf("Cached"), offline.agents.map { it.name })
                assertEquals(listOf("Cached chat"), offline.sessions.lists["agent"]?.sessions?.map { it.title })
                assertEquals(2, offline.sessions.sessions["session"]?.entries?.size)

                attempts.receive()
                server.reachable.complete(Unit)
                val online = app.state.first { it.online && it.agents.firstOrNull()?.name == "Live" }
                assertTrue(online.connection is Connection.Ready)
                assertEquals(2, server.catchUpAfter.await())
                val saved = cache.writes.first { all -> all.any { it.agents.firstOrNull()?.name == "Live" } }.last()
                assertTrue(saved.syncedAt > 1_000)
                assertEquals(2, saved.sessions["session"]?.entries?.size)
            }
        }
    }

    @Test fun resetRemovesServersCredentialsPreferencesAndCachedData() = runBlocking {
        withApp(transport = { awaitCancellation() }) { app, cache ->
            app.state.first { it.connection is Connection.Ready }
            app.resetDevice().join()
            val state = app.state.value
            assertIs<Connection.Unpaired>(state.connection)
            assertTrue(state.onboarding)
            assertEquals(1, state.firstRun)
            assertTrue(state.servers.isEmpty())
            assertTrue(state.agents.isEmpty())
            assertTrue(cache.snapshots.isEmpty())
        }
    }

    private suspend fun withApp(transport: suspend (String) -> FrameConnection, block: suspend (AppViewModel, MemoryCache) -> Unit) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val store = ViewModelStore()
        val prefs = MemoryPreferences()
        val servers = Servers(prefs, credentials = prefs.credentials, transport = transport)
        servers.select(servers.remember(Server.Remote(url), "token", "home"))
        servers.onboardingComplete = true
        val cache = MemoryCache().apply { snapshots[url] = cached }
        val access = DeviceAccess(prefs, object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) = error("Not used")
        })
        val owner = ConnectedServer(Notifications(emptyList()), access)
        val app = AppViewModel(servers, Device("test"), owner, access, cache)
        store.put("app", app)
        try { block(app, cache) } finally { store.clear(); owner.close(); Dispatchers.resetMain() }
    }

    private class MemoryCache : ClientCache {
        val snapshots = mutableMapOf<String, Snapshot>()
        val writes = MutableStateFlow<List<Snapshot>>(emptyList())
        override suspend fun read(server: String) = snapshots[server]
        override suspend fun write(server: String, snapshot: Snapshot) { snapshots[server] = snapshot; writes.value += snapshot }
        override suspend fun remove(server: String) { snapshots -= server }
        override suspend fun clear() = snapshots.clear()
    }

    private class FakeServer : FrameConnection {
        private val frames = Channel<Frame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        val reachable = CompletableDeferred<Unit>()
        val catchUpAfter = CompletableDeferred<Int?>()

        override suspend fun send(frame: Frame) {
            val request = frame as Frame.Request
            when (request.method) {
                Api.Connect.name -> reply(request, Api.Connect, ConnectResult("token", "Home", capabilities = ServerCapabilities("home"), management = false))
                Api.Presence.name -> reply(request, Api.Presence, Unit)
                Api.Agents.name -> reply(request, Api.Agents, listOf(AgentInfo("agent", "Live", "none")))
                Api.Sessions.name -> reply(request, Api.Sessions, SessionPage(listOf(SessionInfo("session", "agent", "Live chat")), false))
                Api.CatchUp.name -> {
                    catchUpAfter.complete(ProtocolJson.decodeFromJsonElement(Api.CatchUp.params, request.params).after)
                    reply(request, Api.CatchUp, Unit)
                }
                Api.Routines.name -> reply(request, Api.Routines, emptyList())
                else -> frames.send(Frame.Response(request.id, false, error = "Unsupported in test: ${request.method}"))
            }
        }

        private suspend fun <P, R> reply(request: Frame.Request, method: Method<P, R>, result: R) {
            frames.send(Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(method.result, result)))
        }

        override suspend fun close() { frames.close() }
    }
}
