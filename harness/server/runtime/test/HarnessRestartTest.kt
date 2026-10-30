package kinuvo.harness.server

import kinuvo.execution.api.*
import kinuvo.harness.server.execution.ExecutionRegistry
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.*

class HarnessRestartTest {
    @Test fun restartRechecksSavedHarnessesAndIsolatesFailures() = runBlocking {
        val configs = listOf("ready", "broken", "timeout").map { HarnessConnection(it, it, ExecutionConnection.Acp(listOf(it))) }
        val catalog = ExecutionCatalog(configs.map { HarnessConnectionStatus(it, ExecutionReadiness(true, true)) }, emptyList())
        val release = CompletableDeferred<Unit>()
        val probes = mutableListOf<String>()
        val lock = Mutex()
        val factory = object : ExecutionFactory {
            override suspend fun probe(config: HarnessConnection): ExecutionProbe {
                release.await()
                lock.withLock { probes += config.id }
                if (config.id == "broken") error("Command missing")
                if (config.id == "timeout") withTimeout(1) { awaitCancellation() }
                return ExecutionProbe(ExecutionReadiness(true, true))
            }
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession = error("Check must not start a Session")
        }
        val peers = object : PeerRegistry {
            override fun authenticate(device: String, token: String?, pairingCode: String?) = "token"
            override fun toolDevices() = emptyList<ToolDevice>()
            override fun setTools(token: String, tools: Set<DeviceToolKind>) = "device"
        }
        repeat(2) {
            val registry = ExecutionRegistry(catalog)
            val server = ServerRuntime(RuntimeFixture().runtime(), peers, executionRegistry = registry, executionFactory = factory)
            try {
                server.start()
                if (!release.isCompleted) assertTrue(registry.catalog().connections.all { it.readiness.reasonCode == "checking" })
                release.complete(Unit)
                withTimeout(5_000) {
                    while (registry.catalog().connections.any { it.readiness.reasonCode == "checking" }) delay(10)
                }
                val statuses = registry.catalog().connections.associateBy { it.config.id }
                assertTrue(statuses.getValue("ready").readiness.online)
                assertFalse(statuses.getValue("broken").readiness.online)
                assertEquals("Command missing", statuses.getValue("broken").readiness.reason)
                assertEquals("unavailable", statuses.getValue("timeout").readiness.reasonCode)
                assertEquals("Harness check timed out", statuses.getValue("timeout").readiness.reason)
            } finally { server.close() }
        }
        assertEquals(2, probes.count { it == "ready" })
        assertEquals(2, probes.count { it == "broken" })
        assertEquals(2, probes.count { it == "timeout" })
    }
}
