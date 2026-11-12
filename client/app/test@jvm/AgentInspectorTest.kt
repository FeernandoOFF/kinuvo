package kinuvo.app

import kinuvo.app.agent.*
import kinuvo.app.connection.*
import kinuvo.app.device.*

import androidx.lifecycle.ViewModelStore
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.model.*
import kinuvo.protocol.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.Executors
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AgentInspectorTest {
    private val serverJobs = mutableListOf<Job>()
    private val agents = listOf(AgentInfo(AgentId("ada"), "Ada", "none"), AgentInfo(AgentId("bob"), "Bob", "none"))

    @Test fun dirtyCloseWaitsOnTheUserAndKeepKeepsTheDraft() = withInspector { vm, _ ->
        vm.openAndEdit(AgentId("ada")) { it.copy(name = "Ava") }
        assertFalse(vm.close())
        assertEquals(InspectorExit.Close, vm.state.value.guard)
        assertTrue(vm.state.value.open)
        vm.resolve(GuardChoice.Keep, agents)
        assertNull(vm.state.value.guard)
        assertEquals("Ava", vm.state.value.draft?.name)
        assertTrue(vm.state.value.dirty)
    }

    @Test fun discardClosesAndDropsTheDraft() = withInspector { vm, script ->
        vm.openAndEdit(AgentId("ada")) { it.copy(name = "Ava") }
        assertFalse(vm.close())
        vm.resolve(GuardChoice.Discard, agents)
        assertEquals(InspectorState(models = vm.state.value.models), vm.state.value)
        assertTrue(script.updates.isEmpty)
    }

    @Test fun cleanInspectorClosesAndLeavesWithoutAsking() = withInspector { vm, _ ->
        vm.open(AgentId("ada"))
        assertTrue(vm.inspect(AgentId("ada")))
        assertTrue(vm.close())
        assertFalse(vm.state.value.open)
        assertTrue(vm.leave())
        vm.open(AgentId("ada"))
        assertTrue(vm.leave())
        assertFalse(vm.state.value.open)
        assertNull(vm.state.value.guard)
    }

    @Test fun inspectingAnotherAgentWhileDirtyAsksThenDiscardSwitches() = withInspector { vm, _ ->
        vm.openAndEdit(AgentId("ada")) { it.copy(soul = "Firm") }
        assertTrue(vm.inspect(AgentId("ada")))
        assertFalse(vm.inspect(AgentId("bob")))
        assertEquals(InspectorExit.Inspect(AgentId("bob")), vm.state.value.guard)
        assertEquals("ada", vm.state.value.agentId?.value)
        vm.resolve(GuardChoice.Keep, agents)
        assertEquals("ada", vm.state.value.agentId?.value)
        assertFalse(vm.inspect(AgentId("bob")))
        vm.resolve(GuardChoice.Discard, agents)
        assertEquals("bob", vm.state.value.agentId?.value)
        vm.state.first { it.draft != null }
        assertEquals("Bob", vm.state.value.draft?.name)
        assertFalse(vm.state.value.dirty)
        assertNull(vm.state.value.guard)
        assertTrue(vm.inspect(AgentId("ada")))
    }

    @Test fun leavingWhileDirtyAsksThenDiscardSignalsLeft() = withInspector { vm, _ ->
        vm.openAndEdit(AgentId("ada")) { it.copy(approvals = "strict") }
        assertFalse(vm.leave())
        assertEquals(InspectorExit.Leave, vm.state.value.guard)
        assertFalse(vm.state.value.left)
        vm.resolve(GuardChoice.Discard, agents)
        assertTrue(vm.state.value.left)
        assertFalse(vm.state.value.open)
        assertNull(vm.state.value.guard)
        vm.leaveHandled()
        assertFalse(vm.state.value.left)
    }

    @Test fun backPopsPagesBeforeGuardingTheClose() = withInspector { vm, _ ->
        vm.openAndEdit(AgentId("ada")) { it.copy(reasoning = "high") }
        vm.push(InspectorPage.Model)
        vm.push(InspectorPage.Model)
        vm.push(InspectorPage.Approvals)
        assertEquals(listOf(InspectorPage.Model, InspectorPage.Approvals), vm.state.value.pages)
        assertTrue(vm.back())
        assertEquals(InspectorPage.Model, vm.state.value.page)
        assertTrue(vm.back())
        assertEquals(InspectorPage.Agent, vm.state.value.page)
        assertNull(vm.state.value.guard)
        assertFalse(vm.back())
        assertEquals(InspectorExit.Close, vm.state.value.guard)
        assertEquals("high", vm.state.value.draft?.reasoning)
    }

    @Test fun saveSendsOnlyTheChangesThenProceeds() = withInspector { vm, script ->
        vm.openAndEdit(AgentId("ada")) { it.copy(name = " Ava ", model = "better") }
        assertFalse(vm.close())
        vm.resolve(GuardChoice.Save, agents)
        vm.state.first { !it.open }
        assertEquals(UpdateAgentParams(AgentId("ada"), name = "Ava", model = "better"), script.updates.receive())
        assertNull(vm.state.value.guard)
    }

    @Test fun saveWithProblemsStaysOpenAndKeepsTheGuard() = withInspector { vm, script ->
        vm.openAndEdit(AgentId("ada")) { it.copy(name = "bob") }
        assertFalse(vm.leave())
        vm.resolve(GuardChoice.Save, agents)
        assertEquals(listOf("Another Agent is called bob"), vm.state.value.errors)
        assertEquals(InspectorExit.Leave, vm.state.value.guard)
        assertTrue(vm.state.value.open)
        assertFalse(vm.state.value.left)
        assertTrue(script.updates.isEmpty)
    }

    @Test fun saveLeavingProceedsWithTheLeave() = withInspector { vm, _ ->
        vm.openAndEdit(AgentId("ada")) { it.copy(soul = "Firm") }
        assertFalse(vm.leave())
        vm.resolve(GuardChoice.Save, agents)
        vm.state.first { it.left }
        assertFalse(vm.state.value.open)
    }

    @Test fun savingWithoutChangesJustRuns() = withInspector { vm, script ->
        vm.open(AgentId("ada"))
        vm.state.first { it.draft != null }
        var ran = false
        vm.save(agents) { ran = true }
        assertTrue(ran)
        assertTrue(script.updates.isEmpty)
    }

    @Test fun anUneditedDraftFollowsSavedSettingsButAnEditedOneStays() = withInspector { vm, _, owner ->
        vm.open(AgentId("ada"))
        vm.state.first { it.draft != null }
        owner.config.updateAgent(UpdateAgentParams(AgentId("ada"), model = "m2"))
        vm.state.first { it.draft?.model == "m2" }
        assertFalse(vm.state.value.dirty)
        vm.edit { it.copy(name = "Ava") }
        owner.config.updateAgent(UpdateAgentParams(AgentId("ada"), model = "m3"))
        vm.state.first { it.details?.settings?.model == "m3" }
        assertEquals("m2", vm.state.value.draft?.model)
        assertEquals("Ava", vm.state.value.draft?.name)
    }

    private suspend fun AgentInspectorViewModel.open(agentId: AgentId) {
        assertTrue(inspect(agentId))
        state.first { it.agentId == agentId && it.draft != null }
    }

    private suspend fun AgentInspectorViewModel.openAndEdit(agentId: AgentId, change: (AgentDraft) -> AgentDraft) {
        open(agentId)
        assertFalse(state.value.dirty)
        edit(change)
        assertTrue(state.value.dirty)
    }

    private fun withInspector(block: suspend (AgentInspectorViewModel, Script) -> Unit) = withInspector { vm, script, _ -> block(vm, script) }

    private fun withInspector(block: suspend (AgentInspectorViewModel, Script, ConnectedServer) -> Unit) = runBlocking {
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
                        val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(prefs, reader))
                        val store = ViewModelStore()
                        try {
                            val script = Script()
                            val client = KinuvoClient("local:test", "test", connection = { script })
                            owner.open(client, "local:test")
                            serverJobs += owner.scope.coroutineContext.job
                            client.connect()
                            owner.activate()
                            val vm = AgentInspectorViewModel(owner.config).also { store.put("inspector", it) }
                            block(vm, script, owner)
                        } finally {
                            store.clearAndJoin()
                            serverJobs += owner.scope.coroutineContext.job
                            owner.close()
                            serverJobs.forEach { it.join() }
                        }
                    }
                }
            } finally { Dispatchers.resetMain() }
        }
    }

    private class Script : FrameConnection {
        private val frames = Channel<Frame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        val updates = Channel<UpdateAgentParams>(Channel.UNLIMITED)
        private val details = mutableMapOf(
            "ada" to AgentDetails(AgentInfo(AgentId("ada"), "Ada", "none"), AgentSettings("m1", "medium", "default", "none"), "Curious", "Kind"),
            "bob" to AgentDetails(AgentInfo(AgentId("bob"), "Bob", "none"), AgentSettings("m1", "low", "strict", "none"), "Blunt", "Fair"),
        )
        override suspend fun send(frame: Frame) {
            val request = frame as Frame.Request
            when (request.method) {
                Api.Connect.name -> reply(request, Api.Connect, ConnectResult("token", "inspector", capabilities = ServerCapabilities("test"), management = true))
                Api.Presence.name -> reply(request, Api.Presence, Unit)
                Api.Agents.name -> reply(request, Api.Agents, details.values.map { it.info })
                Api.Agent.name -> reply(request, Api.Agent, details.getValue(ProtocolJson.decodeFromJsonElement(Api.Agent.params, request.params).agentId.value))
                Api.UpdateAgent.name -> {
                    val p = ProtocolJson.decodeFromJsonElement(Api.UpdateAgent.params, request.params)
                    updates.send(p)
                    val old = details.getValue(p.agentId.value)
                    val next = old.copy(
                        info = old.info.copy(name = p.name ?: old.info.name),
                        settings = old.settings.copy(model = p.model ?: old.settings.model, provider = p.provider ?: old.settings.provider, reasoning = p.reasoning ?: old.settings.reasoning, approvals = p.approvals ?: old.settings.approvals),
                        identity = p.identity ?: old.identity, soul = p.soul ?: old.soul,
                    )
                    details[p.agentId.value] = next
                    reply(request, Api.UpdateAgent, next)
                }
                Api.Models.name -> reply(request, Api.Models, ModelsCatalog())
                Api.Providers.name -> reply(request, Api.Providers, emptyList())
                Api.Skills.name -> reply(request, Api.Skills, emptyList())
                Api.McpServers.name -> reply(request, Api.McpServers, emptyList())
                Api.CatchUp.name -> reply(request, Api.CatchUp, Unit)
                else -> frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Unsupported in test: ${request.method}")))
            }
        }
        private suspend fun <P, R> reply(request: Frame.Request, method: Method<P, R>, result: R) {
            frames.send(Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(method.result, result)))
        }
        override suspend fun close() = Unit
    }
}
