package kinuvo.execution.acp

import kinuvo.execution.api.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class AcpCancellationTest {
    @Test fun cancellationFollowsPromptDispatchWithoutWaitingForAcceptance() = runBlocking {
        val dir = Files.createTempDirectory("kinuvo-acp-cancel").toFile()
        val script = dir.resolve("agent.py")
        val trace = dir.resolve("requests.txt")
        script.writeText("""
            import sys,json
            def emit(v): print(json.dumps(v),flush=True)
            prompt=None
            for line in sys.stdin:
                r=json.loads(line); m=r.get('method')
                with open(sys.argv[1],'a') as log: log.write(str(m)+'\n')
                if m=='initialize': emit({'jsonrpc':'2.0','id':r['id'],'result':{'protocolVersion':2,'info':{'name':'mock','version':'1'},'capabilities':{}}})
                elif m=='session/new': emit({'jsonrpc':'2.0','id':r['id'],'result':{'sessionId':'external'}})
                elif m=='session/prompt': prompt=r
                elif m=='session/cancel' and prompt:
                    emit({'jsonrpc':'2.0','id':prompt['id'],'result':{}})
                    emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'external','update':{'sessionUpdate':'state_update','state':'idle','stopReason':'cancelled'}}})
        """.trimIndent())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val starting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val state = ExecutionState("local", sink = object : ExecutionSink {
            override suspend fun publish(event: AgentEvent) {
                if (event is AgentEvent.ExecutionPhaseChanged && event.phase == ExecutionPhase.Running) {
                    starting.complete(Unit)
                    release.await()
                }
            }
            override suspend fun commit(entries: List<SessionEntry>) {}
        })
        var session: ExecutionSession? = null
        try {
            val config = HarnessConnection("mock", "Mock", ExecutionConnection.Acp(listOf("/usr/bin/env", "python3", script.path, trace.path)))
            val active = AcpExecutionFactory().open(config, AgentRegistration("a", "Agent", "mock", cwd = dir.path), state, ExecutionBroker(state), scope)
            session = active
            withTimeout(10_000) {
                val prompt = async(start = CoroutineStart.UNDISPATCHED) { active.prompt(ExecutionInput("wait")) }
                starting.await()
                val cancel = async(start = CoroutineStart.UNDISPATCHED) { active.cancel() }
                release.complete(Unit)
                cancel.await()
                prompt.await()
                while (state.snapshot().phase != ExecutionPhase.Idle) delay(10)
                assertEquals(listOf("session/prompt", "session/cancel"), trace.readLines().filter { it in setOf("session/prompt", "session/cancel") })
            }
        } finally {
            release.complete(Unit)
            session?.close()
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
