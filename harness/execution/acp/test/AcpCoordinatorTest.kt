@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package kinuvo.execution.acp

import kinuvo.execution.api.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class AcpCoordinatorTest {
    @Test fun noReplayResumeBuffersPreResponseUpdatesRequestsAndRealAcceptance() = runBlocking {
        val dir = Files.createTempDirectory("kinuvo-acp-adapter")
        val script = dir.resolve("mock.py")
        Files.writeString(script, """
            import sys,json
            def emit(v): print(json.dumps(v),flush=True)
            def reply(r,result): emit({'jsonrpc':'2.0','id':r['id'],'result':result})
            def update(v): emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'external-1','update':v}})
            for line in sys.stdin:
                r=json.loads(line); m=r.get('method')
                if m=='initialize': reply(r,{'protocolVersion':r['params']['protocolVersion'],'info':{'name':'mock','version':'1'},'capabilities':{'session':{'prompt':{'image':{},'embeddedContext':{}}}}})
                elif m=='session/resume':
                    assert r['params']['sessionId']=='external-1'
                    assert r['params'].get('replayFrom') is None
                    update({'sessionUpdate':'state_update','state':'idle','stopReason':'end_turn'})
                    update({'sessionUpdate':'state_update','state':'running'})
                    update({'sessionUpdate':'agent_message_chunk','messageId':'early','content':{'type':'text','text':'before response'}})
                    emit({'jsonrpc':'2.0','id':'permission-1','method':'session/request_permission','params':{'sessionId':'external-1','title':'Run?','options':[{'optionId':'yes','name':'Allow','kind':'allow_once'}]}})
                    emit({'jsonrpc':'2.0','id':'permission-2','method':'session/request_permission','params':{'sessionId':'external-1','title':'Again?','options':[{'optionId':'yes','name':'Allow','kind':'allow_once'}]}})
                    reply(r,{})
                elif m=='session/prompt':
                    update({'sessionUpdate':'user_message','messageId':'accepted-real','content':r['params']['prompt']})
                    reply(r,{'messageId':'accepted-real'})
                    update({'sessionUpdate':'agent_message_chunk','messageId':'answer','content':{'type':'text','text':'hello'}})
                elif m=='session/cancel': update({'sessionUpdate':'state_update','state':'idle','stopReason':'cancelled'})
                elif r.get('id') in ['permission-1','permission-2']:
                    assert r['result']['outcome']['outcome']=='selected'
        """.trimIndent())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = mutableListOf<AgentEvent>()
        val batches = mutableListOf<List<SessionEntry>>()
        val state = ExecutionState("s", initial = ExecutionSnapshot("s", externalSessionId = "external-1"), sink = object : ExecutionSink {
            override suspend fun publish(event: AgentEvent) { synchronized(events) { events += event } }
            override suspend fun commit(entries: List<SessionEntry>) { synchronized(batches) { batches += entries } }
        })
        val broker = ExecutionBroker(state)
        val config = HarnessConnection("mock", "Mock", ExecutionConnection.Acp(listOf("/usr/bin/env", "python3", script.toString())))
        var session: ExecutionSession? = null
        try {
            session = withTimeout(10_000) { AcpExecutionFactory().open(config, AgentRegistration("external", "External", "mock", cwd = dir.toString()), state, broker, scope) }
            withTimeout(10_000) { while (state.live().entries.isEmpty() || state.snapshot().pending.size != 2) delay(10) }
            assertEquals("before response", (state.live().entries.single() as SessionEntry.AssistantMessage).text)
            assertTrue(synchronized(events) { events.none { it is AgentEvent.RunFinished } })
            assertEquals(ExecutionPhase.Running, state.snapshot().phase)
            assertTrue("image/png" in session.capabilities.supportedMimeTypes)
            val requests = state.snapshot().pending
            assertNotEquals(requests[0].id, requests[1].id)
            assertIs<ExecutionPrompt.Approval>(requests[0].prompt)
            assertFailsWith<IllegalArgumentException> { broker.answer(requests[0].id, buildJsonObject { put("optionId", "bad") }) }
            assertEquals(2, state.snapshot().pending.size)
            requests.forEach { assertTrue(broker.answer(it.id, buildJsonObject { put("optionId", "yes") })) }
            assertFalse(broker.answer(requests[0].id, JsonNull))
            assertEquals("accepted-real", session.prompt(ExecutionInput("same")).messageId)
            withTimeout(10_000) { while (state.live().entries.size < 2) delay(10) }
            assertTrue(state.live().entries.none { it is SessionEntry.UserMessage })
            session.cancel()
            withTimeout(10_000) { while (state.snapshot().phase != ExecutionPhase.Idle) delay(10) }
            assertIs<SessionEntry.Notice>(synchronized(batches) { batches.single().single() })
            session.close()
            assertEquals(ExecutionPhase.Lost, state.snapshot().phase)
        } finally {
            session?.close(); scope.cancel()
            Files.deleteIfExists(script); Files.deleteIfExists(dir)
        }
    }
}
