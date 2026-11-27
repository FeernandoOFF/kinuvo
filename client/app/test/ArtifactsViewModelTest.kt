package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.artifacts.*
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.navigation.*
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.artifact.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactsViewModelTest {
    @Test fun editsKeepTheDraftWhenTheArtifactChangesUnderneath() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val frames = Channel<Frame>(Channel.UNLIMITED)
        var info = ArtifactInfo("a1", "Page", "html", AgentId("main"), SessionId("s1"), 1, 1)
        var content = "<p>a</p>"
        var failSave = false
        val transport = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                val payload = when (request.method) {
                    "connect" -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.Presence.name -> buildJsonObject {}
                    Api.Artifacts.name -> ProtocolJson.encodeToJsonElement(listOf(info))
                    Api.Artifact.name -> ProtocolJson.encodeToJsonElement(ArtifactData(info, content))
                    Api.SaveArtifact.name -> {
                        if (failSave) { frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Too big"))); return }
                        val save = ProtocolJson.decodeFromJsonElement<ArtifactSave>(request.params)
                        content = save.content
                        info = info.copy(updated = info.updated + 1)
                        ProtocolJson.encodeToJsonElement(info)
                    }
                    else -> { frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Unused in test"))); return }
                }
                frames.send(Frame.Response(request.id, true, payload))
            }
            override suspend fun close() { frames.close() }
        }
        val client = KinuvoClient("local:test", "test", connection = { transport }, dispatcher = StandardTestDispatcher(testScheduler))
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) {}
        }
        val server = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
        val store = ViewModelStore()
        try {
            client.connect(); server.open(client, "local:test"); server.config.attach(client)
            val vm = ArtifactsViewModel(Route.Artifacts("a1"), server)
            store.put("artifacts", vm)
            runCurrent()
            assertEquals(content, vm.state.value.open?.content)

            vm.edit()
            vm.change("<p>mine</p>")
            assertTrue(vm.state.value.dirty)
            assertFalse(vm.state.value.stale)

            info = info.copy(updated = 2); content = "<p>agent</p>"
            frames.send(Frame.Event(Api.ArtifactChanged.name, ProtocolJson.encodeToJsonElement(ArtifactChange("a1", info)), 1))
            runCurrent()
            assertEquals("<p>mine</p>", vm.state.value.draft?.text)
            assertTrue(vm.state.value.stale)
            vm.keepMine()
            assertFalse(vm.state.value.stale)

            failSave = true
            vm.save(); runCurrent()
            assertEquals("<p>mine</p>", vm.state.value.draft?.text)
            assertTrue(vm.state.value.errors.isNotEmpty())
            assertFalse(vm.state.value.saving)

            failSave = false
            vm.save(); runCurrent()
            assertNull(vm.state.value.draft)
            assertEquals("<p>mine</p>", content)
            assertEquals("<p>mine</p>", vm.state.value.open?.content)

            vm.edit(); vm.cancel()
            assertNull(vm.state.value.draft)

            vm.saveDesign("""{"artboards":[]}""")
            assertEquals("""{"artboards":[]}""", vm.state.value.open?.content)
            runCurrent()
            assertEquals("""{"artboards":[]}""", content)
            assertEquals(content, vm.state.value.open?.content)
            assertNull(vm.state.value.draft)

            failSave = true
            vm.saveDesign("x"); runCurrent()
            assertTrue(vm.state.value.errors.isNotEmpty())
            assertEquals("""{"artboards":[]}""", vm.state.value.open?.content)
        } finally { store.clear(); server.close(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun aFolderListsItsFilesAndReadsOneInChunks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val info = ArtifactInfo("a1", "App", "html", AgentId("main"), SessionId("s1"), 1, 1, bundle = true)
        val wasm = ByteArray(2500) { it.toByte() }
        val asked = mutableListOf<Long>()
        val transport = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                val payload = when (request.method) {
                    "connect" -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.Presence.name -> buildJsonObject {}
                    Api.Artifacts.name -> ProtocolJson.encodeToJsonElement(listOf(info))
                    Api.Artifact.name -> ProtocolJson.encodeToJsonElement(ArtifactData(info, "<p/>"))
                    Api.ArtifactFileList.name -> ProtocolJson.encodeToJsonElement(ArtifactFiles("a1", "index.html", listOf(ArtifactFile("index.html", 4, "text/html", "h"), ArtifactFile("m.wasm", wasm.size.toLong(), "application/wasm", "w"))))
                    Api.ArtifactFileRead.name -> {
                        val read = ProtocolJson.decodeFromJsonElement<ArtifactFileRef>(request.params)
                        if (read.path != "m.wasm") { frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "No file"))); return }
                        asked += read.offset
                        val part = wasm.copyOfRange(read.offset.toInt(), minOf(wasm.size, read.offset.toInt() + 1000))
                        ProtocolJson.encodeToJsonElement(ArtifactFileChunk("m.wasm", read.offset, wasm.size.toLong(), "application/wasm", kotlin.io.encoding.Base64.encode(part)))
                    }
                    else -> { frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Unused in test"))); return }
                }
                frames.send(Frame.Response(request.id, true, payload))
            }
            override suspend fun close() { frames.close() }
        }
        val client = KinuvoClient("local:test", "test", connection = { transport }, dispatcher = StandardTestDispatcher(testScheduler))
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) {}
        }
        val server = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
        val store = ViewModelStore()
        try {
            client.connect(); server.open(client, "local:test"); server.config.attach(client)
            val vm = ArtifactsViewModel(Route.Artifacts("a1"), server)
            store.put("artifacts", vm)
            runCurrent()
            assertEquals(listOf("index.html", "m.wasm"), vm.state.value.files?.files?.map { it.path })

            var read: ByteArray? = null
            val job = launch { read = vm.readFile("a1", "m.wasm") }
            runCurrent(); job.join()
            assertEquals(wasm.toList(), read?.toList())
            assertEquals(listOf(0L, 1000L, 2000L), asked)

            var missing: ByteArray? = byteArrayOf(1)
            launch { missing = vm.readFile("a1", "nope.js") }
            runCurrent()
            assertNull(missing)
        } finally { store.clear(); server.close(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun aFolderShowsWhatItAsksForAndGrantsAreSentToTheServer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val info = ArtifactInfo("a1", "App", "html", AgentId("main"), SessionId("s1"), 1, 1, bundle = true)
        val asks = ArtifactCapabilities(network = listOf("https://api.example.com"), popups = true)
        var permissions = ArtifactPermissions("a1", asks)
        var predatesGrants = false
        val changes = mutableListOf<ArtifactGrantChange>()
        val transport = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                val payload = when (request.method) {
                    "connect" -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.Presence.name -> buildJsonObject {}
                    Api.Artifacts.name -> ProtocolJson.encodeToJsonElement(listOf(info))
                    Api.Artifact.name -> ProtocolJson.encodeToJsonElement(ArtifactData(info, "<p/>"))
                    Api.ArtifactFileList.name -> ProtocolJson.encodeToJsonElement(ArtifactFiles("a1", "index.html", listOf(ArtifactFile("index.html", 4, "text/html", "h"))))
                    Api.ArtifactPermissionList.name -> {
                        if (predatesGrants) { frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Unknown method"))); return }
                        ProtocolJson.encodeToJsonElement(permissions)
                    }
                    Api.ChangeArtifactGrants.name -> {
                        val change = ProtocolJson.decodeFromJsonElement<ArtifactGrantChange>(request.params)
                        changes += change
                        permissions = permissions.copy(granted = asks, effective = asks)
                        ProtocolJson.encodeToJsonElement(permissions)
                    }
                    else -> { frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Unused in test"))); return }
                }
                frames.send(Frame.Response(request.id, true, payload))
            }
            override suspend fun close() { frames.close() }
        }
        val client = KinuvoClient("local:test", "test", connection = { transport }, dispatcher = StandardTestDispatcher(testScheduler))
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) {}
        }
        val server = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
        val store = ViewModelStore()
        try {
            client.connect(); server.open(client, "local:test"); server.config.attach(client)
            val vm = ArtifactsViewModel(Route.Artifacts("a1"), server)
            store.put("artifacts", vm)
            runCurrent()
            assertEquals(asks, vm.state.value.permissions?.requested)
            assertTrue(vm.state.value.permissions?.pending == true)
            assertTrue(vm.state.value.canGrant)

            vm.allowAll(true); runCurrent()
            assertEquals(ArtifactGrantChange("a1", all = true), changes.single())
            assertTrue(vm.state.value.permissions?.everything == true)

            vm.revoke(ArtifactCapabilities(popups = true)); vm.grant(ArtifactCapabilities(network = listOf("https://api.example.com"))); runCurrent()
            assertEquals(listOf(ArtifactGrantChange("a1", revoke = ArtifactCapabilities(popups = true)), ArtifactGrantChange("a1", grant = ArtifactCapabilities(network = listOf("https://api.example.com")))), changes.drop(1))

            predatesGrants = true
            frames.send(Frame.Event(Api.ArtifactChanged.name, ProtocolJson.encodeToJsonElement(ArtifactChange("a1", info)), 1))
            runCurrent()
            assertNull(vm.state.value.permissions)
            assertEquals(listOf("index.html"), vm.state.value.files?.files?.map { it.path })
            assertTrue(vm.state.value.errors.isEmpty())
        } finally { store.clear(); server.close(); client.close(); Dispatchers.resetMain() }
    }
}
