package kinuvo.execution.acp

import kinuvo.execution.api.*
import kinuvo.harness.server.execution.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class AcpCoordinatorTest {
    @Test fun acceptanceOutputIdleCancellationAndBrokerReconnect() = runBlocking {
        val dir = Files.createTempDirectory("kinuvo-acp-coordinator")
        val script = dir.resolve("mock.py")
        Files.writeString(script, """
            import sys,json
            def emit(v): print(json.dumps(v),flush=True)
            def update(v): emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'external-1','update':v}})
            for line in sys.stdin:
                r=json.loads(line); m=r.get('method')
                if m=='initialize': emit({'jsonrpc':'2.0','id':r['id'],'result':{'protocolVersion':r['params']['protocolVersion'],'info':{'name':'mock','version':'1'},'capabilities':{'session':{}}}})
                elif m=='session/new': emit({'jsonrpc':'2.0','id':r['id'],'result':{'sessionId':'external-1'}})
                elif m=='session/resume':
                    assert r['params']['sessionId']=='external-1'
                    assert r['params']['replayFrom']['type']=='start'
                    update({'sessionUpdate':'agent_message','messageId':'offline','content':[{'type':'text','text':'Recorded while disconnected'}]})
                    emit({'jsonrpc':'2.0','id':r['id'],'result':{}})
                elif m=='session/prompt':
                    emit({'jsonrpc':'2.0','id':r['id'],'result':{}})
                    update({'sessionUpdate':'state_update','state':'running'})
                    text=r['params']['prompt'][0]['text']
                    if text=='permission':
                        emit({'jsonrpc':'2.0','id':'permission-1','method':'session/request_permission','params':{'sessionId':'external-1','title':'Run?','options':[{'optionId':'yes','name':'Allow','kind':'allow_once'}]}})
                    elif text=='question':
                        emit({'jsonrpc':'2.0','id':'question-1','method':'elicitation/create','params':{'sessionId':'external-1','message':'Name?','mode':'form','requestedSchema':{'type':'object','properties':{'name':{'type':'string'}}}}})
                    elif text!='wait':
                        update({'sessionUpdate':'agent_message_chunk','messageId':'answer','content':{'type':'text','text':'hello'}})
                        update({'sessionUpdate':'agent_message','messageId':'answer','content':[{'type':'text','text':'hello world'}]})
                        update({'sessionUpdate':'state_update','state':'idle','stopReason':'end_turn'})
                elif m=='session/cancel': update({'sessionUpdate':'state_update','state':'idle','stopReason':'cancelled'})
                elif r.get('id') in ['permission-1','question-1']:
                    update({'sessionUpdate':'state_update','state':'idle','stopReason':'end_turn'})
        """.trimIndent())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = ExecutionRegistry()
        registry.save(HarnessConnection("mock", "Mock", ExecutionConnection.Acp(listOf("/usr/bin/env", "python3", script.toString()))))
        registry.register(AgentRegistration("external", "External", "mock", cwd = dir.toString()))
        var disk = emptyList<ExternalSession>()
        val coordinator = ExecutionCoordinator(registry, AcpExecutionFactory(), scope, persist = { disk = it })
        suspend fun awaitPhase(id: String, phase: ExecutionPhase) = withTimeout(10000) {
            while(coordinator.snapshot(id).phase != phase) delay(10)
        }
        try {
            val id = coordinator.create("external").id
            coordinator.send(SendParams("external", id, "hello"))
            awaitPhase(id, ExecutionPhase.Idle)
            assertEquals("hello world", coordinator.snapshot(id).messages.last().text)
            assertEquals(1, coordinator.snapshot(id).messages.count { it.role == "assistant" })
            assertEquals("external-1", coordinator.snapshot(id).externalSessionId)
            coordinator.send(SendParams("external", id, "wait"))
            delay(100)
            assertEquals(ExecutionPhase.Running, coordinator.snapshot(id).phase)
            coordinator.stop(id)
            awaitPhase(id, ExecutionPhase.Idle)
            coordinator.send(SendParams("external", id, "permission"))
            val request = withTimeout(10000) { while(coordinator.snapshot(id).pending.isEmpty()) delay(10); coordinator.snapshot(id).pending.single() }
            // A newly connected client retrieves the same Server-owned snapshot.
            assertEquals(request, coordinator.snapshot(id).pending.single())
            val answer = ExecutionAnswer(id, request.id, Json.parseToJsonElement("""{"outcome":{"outcome":"selected","optionId":"yes"}}"""))
            assertTrue(coordinator.answer(answer))
            assertFalse(coordinator.answer(answer))
            awaitPhase(id, ExecutionPhase.Idle)
            coordinator.send(SendParams("external", id, "question"))
            val question = withTimeout(10000) { while(coordinator.snapshot(id).pending.isEmpty()) delay(10); coordinator.snapshot(id).pending.single() }
            assertEquals(ExecutionRequestKind.Question, question.kind)
            assertTrue(coordinator.answer(ExecutionAnswer(id, question.id, Json.parseToJsonElement("""{"action":"decline"}"""))))
            awaitPhase(id, ExecutionPhase.Idle)
            val transcript = coordinator.snapshot(id).messages
            val replayedTranscript = transcript + ExecutionMessage("offline", "assistant", "Recorded while disconnected")
            suspend fun resumeAndReplay(c: ExecutionCoordinator): List<ExecutionMessage> {
                c.resume(id)
                withTimeout(10000) { while (c.snapshot(id).messages.none { it.id == "offline" }) delay(10) }
                return c.snapshot(id).messages
            }
            repeat(2) {
                coordinator.closeExecution(id)
                assertFalse(coordinator.inUse("mock"))
                assertEquals(ExecutionPhase.Lost, coordinator.snapshot(id).phase)
                assertEquals(replayedTranscript, resumeAndReplay(coordinator))
                assertEquals("external-1", coordinator.snapshot(id).externalSessionId)
                assertTrue(coordinator.inUse("mock"))
            }
            coordinator.closeExecution(id)
            val restored = ExecutionCoordinator(registry, AcpExecutionFactory(), scope, disk)
            assertEquals(ExecutionPhase.Lost, restored.snapshot(id).phase)
            assertEquals(coordinator.snapshot(id).messages, restored.snapshot(id).messages)
            try {
                assertEquals(replayedTranscript, resumeAndReplay(restored))
                restored.send(SendParams("external", id, "hello again"))
                withTimeout(10000) { while (restored.snapshot(id).phase != ExecutionPhase.Idle) delay(10) }
            } finally { restored.closeExecution(id) }
        } finally {
            coordinator.close()
            scope.cancel()
            Files.deleteIfExists(script); Files.deleteIfExists(dir)
        }
    }
}
