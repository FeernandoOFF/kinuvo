package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.team.TeamViewModel
import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.team.*
import kinuvo.testing.server.TestServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.util.concurrent.Executors
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TeamViewModelTest {
    @Test fun mentionsAndMemberAttributionComeFromRealServer() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Dispatchers.setMain(main)
            try { withContext(main) { withTimeout(15000) {
                TestServer.start(script = { reply("second reply") }, scenario = {
                    agent("first"); agent("second")
                }).use { server ->
                    val reader = object : DeviceReader {
                        override val kinds = emptyList<String>()
                        override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
                        override fun read(requestJson: String, completion: (String) -> Unit) = error("Unused")
                    }
                    val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
                    val stores = ViewModelStore()
                    try {
                        val client = server.client(token = server.auth.sessionToken())
                        owner.open(client, "local:test"); client.connect(); owner.activate()
                        val team = client.call(Api.CreateTeam, TeamSpec("Work", listOf(AgentId("first"), AgentId("second")))).team
                        val vm = TeamViewModel(owner.teams, team.id); stores.put("team", vm)
                        vm.state.first { it.members.size == 2 }
                        vm.mention(AgentId("second")); vm.draft(vm.state.value.draft + "hello")
                        assertEquals("@second hello", vm.state.value.draft)
                        vm.send()
                        val state = vm.state.first { it.items.any { row -> row.kind == TeamEntryKind.Reply } }
                        assertEquals(AgentId("second"), state.items.last { it.kind == TeamEntryKind.Reply }.agentId)
                        assertEquals("@second hello", state.items.first().text)
                    } finally { stores.clear(); owner.close() }
                }
            } } } finally { Dispatchers.resetMain() }
        }
    }
}
