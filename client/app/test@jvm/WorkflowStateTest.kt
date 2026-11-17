package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.team.NewTeamViewModel
import kinuvo.app.workflow.*
import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.team.TeamSpec
import kinuvo.protocol.workflow.*
import kinuvo.testing.server.TestServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.Executors
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowStateTest {
    private val agent = AgentId("first")
    private val seed = Workflow(WorkflowId("w1"), "Flow", listOf(WorkflowStep.AgentTurn(StepId("step-1"), agent, "go")))

    private fun withServer(block: suspend (ConnectedServer, kotlinx.coroutines.CoroutineScope) -> Unit) = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Dispatchers.setMain(main)
            try { withContext(main) { withTimeout(15000) {
                TestServer.start(script = { reply("ok") }, scenario = { agent("first"); agent("second") }).use { server ->
                    val reader = object : DeviceReader {
                        override val kinds = emptyList<String>()
                        override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
                        override fun read(requestJson: String, completion: (String) -> Unit) = error("Unused")
                    }
                    val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
                    try {
                        val client = server.client(token = server.auth.sessionToken())
                        owner.open(client, "local:test"); client.connect(); owner.activate()
                        client.call(Api.CreateWorkflow, seed)
                        block(owner, this)
                    } finally { owner.close() }
                }
            } } } finally { Dispatchers.resetMain() }
        }
    }

    @Test fun pageChangesReplaceOnlyTheGraphAndDiscardReloadsThePage() = withServer { owner, _ ->
        val stores = ViewModelStore()
        try {
            val vm = WorkflowViewModel(owner.workflows, seed.id).also { stores.put("w", it) }
            vm.state.first { it.draft != null }
            assertFalse(vm.state.value.dirty)
            vm.edit(vm.state.value.draft!!.copy(name = "Renamed", agentId = AgentId("second")))
            val tool = WorkflowStep.Tool(StepId("t"), "echo", buildJsonObject { put("a", "{{steps.step-1.output}}") }, Position(3f, 4f))
            val edited = seed.copy(name = "Stale", agentId = null, steps = seed.steps + tool, edges = listOf(WorkflowEdge(StepId("step-1"), tool.id)))
            vm.graphChanged(edited)
            val draft = vm.state.value.draft!!
            assertEquals(edited.steps, draft.steps)
            assertEquals(edited.edges, draft.edges)
            assertEquals("Renamed", draft.name)
            assertEquals(AgentId("second"), draft.agentId)
            assertTrue(vm.state.value.dirty)
            assertEquals(0, vm.state.value.generation)
            vm.discard()
            assertFalse(vm.state.value.dirty)
            assertEquals(vm.state.value.workflow, vm.state.value.draft)
            assertEquals(1, vm.state.value.generation)
        } finally { stores.clear() }
    }

    @Test fun movingAStepSavesItsPlaceWithoutMakingTheDraftDirty() = withServer { owner, _ ->
        val stores = ViewModelStore()
        try {
            val vm = WorkflowViewModel(owner.workflows, seed.id).also { stores.put("w", it) }
            vm.state.first { it.draft != null }
            vm.edit(vm.state.value.draft!!.copy(name = "Renamed"))
            val moved = seed.copy(steps = seed.steps.map { it.placed(Position(30f, 40f)) })
            vm.graphChanged(moved)
            vm.state.first { it.workflow?.steps == moved.steps }
            val state = vm.state.value
            assertEquals("Flow", state.workflow!!.name)
            assertEquals("Renamed", state.draft!!.name)
            assertEquals(moved.steps, state.draft!!.steps)
            assertEquals(0, state.generation)
            assertTrue(state.dirty)
            vm.discard()
            assertFalse(vm.state.value.dirty)
            vm.graphChanged(seed.copy(steps = seed.steps.map { it.placed(Position(1f, 2f)) }))
            assertFalse(vm.state.value.dirty)
            vm.state.first { it.workflow?.steps?.single()?.position == Position(1f, 2f) }
            assertFalse(vm.state.value.dirty)
        } finally { stores.clear() }
    }

    @Test fun savedDraftBecomesTheWorkflow() = withServer { owner, _ ->
        val stores = ViewModelStore()
        try {
            val vm = WorkflowViewModel(owner.workflows, seed.id).also { stores.put("w", it) }
            vm.state.first { it.draft != null }
            vm.edit(vm.state.value.draft!!.copy(name = "Renamed"))
            assertTrue(vm.state.value.dirty)
            vm.save()
            vm.state.first { it.workflow?.name == "Renamed" && !it.dirty && !it.saving }
        } finally { stores.clear() }
    }

    @Test fun teamCreationFailureIsShownAndSuccessReportsTheId() = withServer { owner, _ ->
        val stores = ViewModelStore()
        try {
            val vm = NewTeamViewModel(owner.teams).also { stores.put("t", it) }
            var created: Any? = null
            vm.create(TeamSpec("Empty", emptyList())) { created = it }
            vm.state.first { it.error != null }
            assertNull(created)
            val ok = CompletableDeferred<Any>()
            vm.create(TeamSpec("Work", listOf(agent, AgentId("second")))) { ok.complete(it) }
            assertNotNull(ok.await())
        } finally { stores.clear() }
    }
}
