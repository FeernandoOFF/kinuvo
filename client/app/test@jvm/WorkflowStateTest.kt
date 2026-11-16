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

    @Test fun draftEditsDiscardAndStepIds() = withServer { owner, _ ->
        val stores = ViewModelStore()
        try {
            val vm = WorkflowViewModel(owner.workflows, seed.id).also { stores.put("w", it) }
            vm.state.first { it.draft != null }
            assertFalse(vm.state.value.dirty)
            val agents = listOf(AgentInfo(agent, "First", "exec"))
            val first = vm.state.value.draft!!.steps.first()
            val added = vm.add(StepKind.Agent, agents, first)
            val approval = vm.add(StepKind.Approval, agents, null)
            assertEquals(StepId("step-2"), added)
            assertEquals(StepId("approval-1"), approval)
            assertTrue(vm.state.value.dirty)
            assertEquals(listOf(WorkflowEdge(first.id, added)), vm.state.value.draft!!.edges)
            vm.remove(added)
            assertEquals(listOf(first.id, approval), vm.state.value.draft!!.steps.map { it.id })
            assertTrue(vm.state.value.draft!!.edges.isEmpty())
            vm.discard()
            assertFalse(vm.state.value.dirty)
            assertEquals(vm.state.value.workflow, vm.state.value.draft)
            assertEquals(1, vm.state.value.generation)
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
