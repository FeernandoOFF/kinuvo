package kinuvo.harness.server

import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class ExternalServerTest {
    @Test fun externalSessionSurvivesClientReconnectAndCatalogSurvivesRestart() = runBlocking {
        val dir = Files.createTempDirectory("kinuvo-external-server").toFile()
        val script = dir.resolve("agent.py")
        script.writeText("""
            import sys,json
            def emit(v): print(json.dumps(v),flush=True)
            for line in sys.stdin:
                r=json.loads(line); m=r.get('method')
                if m=='initialize': emit({'jsonrpc':'2.0','id':r['id'],'result':{'protocolVersion':r['params']['protocolVersion'],'info':{'name':'mock','version':'1'},'capabilities':{'session':{}}}})
                elif m=='session/new': emit({'jsonrpc':'2.0','id':r['id'],'result':{'sessionId':'external'}})
                elif m=='session/resume':
                    assert r['params']['sessionId']=='external'
                    emit({'jsonrpc':'2.0','id':r['id'],'result':{}})
                elif m=='session/prompt':
                    emit({'jsonrpc':'2.0','id':r['id'],'result':{}})
                    text=r['params']['prompt'][0]['text']
                    if text=='permission': emit({'jsonrpc':'2.0','id':'p','method':'session/request_permission','params':{'sessionId':'external','title':'Run?','options':[{'optionId':'yes','name':'Allow','kind':'allow_once'}]}})
                    if text=='question': emit({'jsonrpc':'2.0','id':'q','method':'elicitation/create','params':{'sessionId':'external','message':'Sign in','mode':'url','url':'https://example.test/sign-in','elicitationId':'login-1'}})
                elif r.get('id') in ['p','q']: emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'external','update':{'sessionUpdate':'state_update','state':'idle','stopReason':'end_turn'}}})
                elif m=='session/cancel': emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'external','update':{'sessionUpdate':'state_update','state':'idle','stopReason':'cancelled'}}})
        """.trimIndent())
        var server = HarnessServer(Harness(home = Path(dir.path)))
        var client: KinuvoClient? = null
        try {
            val port = server.start("127.0.0.1", 0, schedule = false)
            val first = KinuvoClient("ws://127.0.0.1:$port/ws", "manager", pairingCode = server.auth.pairing(false, true)!!.code)
            client = first
            val token = first.connect().token
            val connection = HarnessConnection("mock", "Mock", ExecutionConnection.Acp(listOf("/usr/bin/env", "python3", script.path)))
            first.call(Api.SaveHarnessConnection, connection)
            first.call(Api.RegisterExternalAgent, AgentRegistration("external", "External", "mock", cwd = dir.path))
            assertEquals(ExecutionKind.Acp, first.call(Api.Agents, Unit).first { it.id == "external" }.executionKind)
            assertTrue(first.call(Api.ProbeHarnessConnection, connection).readiness.online)
            val id = first.call(Api.CreateSession, AgentRef("external")).id
            first.call(Api.Send, SendParams("external", id, "wait"))
            first.close()
            val second = KinuvoClient("ws://127.0.0.1:$port/ws", "manager", token = token)
            client = second
            second.connect()
            assertEquals(ExecutionPhase.Running, second.call(Api.ExecutionSnapshot, ExecutionRef(id)).phase)
            second.call(Api.CatchUp, CatchUpParams("external", id))
            assertEquals("wait", (second.call(Api.History, HistoryParams("external", id, 100)).single().entry as SessionEntry.UserMessage).text)
            assertFails { second.call(Api.SaveHarnessConnection, connection) }
            second.call(Api.RegisterExternalAgent, AgentRegistration("unused", "Unused", "mock", cwd = dir.path))
            second.call(Api.UnregisterExternalAgent, ExecutionRef("unused"))
            assertFails { second.call(Api.UnregisterExternalAgent, ExecutionRef("external")) }
            second.call(Api.Stop, StopParams(id))
            withTimeout(5000) { while(second.call(Api.ExecutionSnapshot, ExecutionRef(id)).phase != ExecutionPhase.Idle) delay(10) }
            second.call(Api.Send, SendParams("external", id, "permission"))
            withTimeout(5000) { while(second.call(Api.ExecutionSnapshot, ExecutionRef(id)).pending.isEmpty()) delay(10) }
            second.close()
            val responder = KinuvoClient("ws://127.0.0.1:$port/ws", "chat-device", pairingCode = server.auth.pairing(false, false)!!.code)
            client = responder
            responder.connect()
            assertFails { responder.call(Api.ExecutionCatalog, Unit) }
            assertFails { responder.call(Api.SaveHarnessConnection, connection) }
            val approval = responder.call(Api.ExecutionSnapshot, ExecutionRef(id)).pending.single()
            assertEquals("Allow", approval.options.single().label)
            val answer = ExecutionAnswer(id, approval.id, kotlinx.serialization.json.Json.parseToJsonElement("""{"outcome":{"outcome":"selected","optionId":"yes"}}"""))
            assertTrue(responder.call(Api.ExecutionAnswer, answer))
            assertFalse(responder.call(Api.ExecutionAnswer, answer))
            withTimeout(5000) { while(responder.call(Api.ExecutionSnapshot, ExecutionRef(id)).phase != ExecutionPhase.Idle) delay(10) }
            responder.call(Api.Send, SendParams("external", id, "question"))
            withTimeout(5000) { while(responder.call(Api.ExecutionSnapshot, ExecutionRef(id)).pending.isEmpty()) delay(10) }
            val question = responder.call(Api.ExecutionSnapshot, ExecutionRef(id)).pending.single()
            assertEquals(ExecutionRequestKind.Question, question.kind)
            assertTrue(question.payload.toString().contains("https://example.test/sign-in"))
            assertTrue(responder.call(Api.ExecutionAnswer, ExecutionAnswer(id, question.id, kotlinx.serialization.json.Json.parseToJsonElement("""{"action":"decline"}"""))))
            withTimeout(5000) { while(responder.call(Api.ExecutionSnapshot, ExecutionRef(id)).phase != ExecutionPhase.Idle) delay(10) }
            responder.close()
            server.stop()
            server = HarnessServer(Harness(home = Path(dir.path)))
            val restartedPort = server.start("127.0.0.1", 0, schedule = false)
            val third = KinuvoClient("ws://127.0.0.1:$restartedPort/ws", "manager", token = token)
            client = third
            third.connect()
            assertEquals("external", third.call(Api.ExecutionCatalog, Unit).agents.single().id)
            assertEquals(ExecutionPhase.Lost, third.call(Api.ExecutionSnapshot, ExecutionRef(id)).phase)
            assertEquals("external", third.call(Api.ExecutionSnapshot, ExecutionRef(id)).externalSessionId)
            val saved = third.call(Api.ExecutionSnapshot, ExecutionRef(id)).messages
            val resumed = third.call(Api.ResumeExecution, ExecutionRef(id))
            assertEquals(id, resumed.sessionId)
            assertEquals(saved, resumed.messages)
            assertEquals("external", resumed.externalSessionId)
            third.call(Api.CloseExecution, ExecutionRef(id))
            third.call(Api.UnregisterExternalAgent, ExecutionRef("external"))
            third.call(Api.CloseExecution, ExecutionRef(id))
            assertEquals(saved, third.call(Api.ExecutionSnapshot, ExecutionRef(id)).messages)
        } finally { client?.close(); server.stop(); dir.deleteRecursively() }
    }
}
