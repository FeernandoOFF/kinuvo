package kinuvo.harness.server

import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class ExternalServerTest {
    @Test fun outputImageUsesProductionStorageAndSurvivesRestart() = runBlocking {
        val dir = Files.createTempDirectory("kinuvo-acp-media").toFile()
        val script = dir.resolve("media.py")
        val image = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jP1sAAAAASUVORK5CYII="
        script.writeText("""
            import sys,json
            def emit(v): print(json.dumps(v),flush=True)
            for line in sys.stdin:
                r=json.loads(line); m=r.get('method')
                if m=='initialize': emit({'jsonrpc':'2.0','id':r['id'],'result':{'protocolVersion':r['params']['protocolVersion'],'info':{'name':'media','version':'1'},'capabilities':{'session':{}}}})
                elif m=='session/new': emit({'jsonrpc':'2.0','id':r['id'],'result':{'sessionId':'media'}})
                elif m=='session/prompt':
                    emit({'jsonrpc':'2.0','id':r['id'],'result':{'messageId':'accepted-media'}})
                    emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'media','update':{'sessionUpdate':'agent_message_chunk','messageId':'m','content':{'type':'image','mimeType':'image/png','data':'$image'}}}})
                    emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'media','update':{'sessionUpdate':'state_update','state':'idle','stopReason':'end_turn'}}})
        """.trimIndent())
        val server = HarnessServer(Harness(home = Path(dir.path)))
        var client: KinuvoClient? = null
        try {
            val port = server.start("127.0.0.1", 0, schedule = false)
            val connected = KinuvoClient("ws://127.0.0.1:$port/ws", "manager", token = server.auth.sessionToken())
            client = connected; connected.connect()
            connected.call(Api.SaveHarnessConnection, HarnessConnection("media", "Media", ExecutionConnection.Acp(listOf("python3", script.path))))
            connected.call(Api.RegisterExternalAgent, AgentRegistration("media", "Media", "media", cwd = dir.path))
            val id = connected.call(Api.CreateSession, AgentRef("media")).id
            connected.call(Api.Send, SendParams("media", id, "image", submissionId = "image"))
            val attachment = withTimeout(10_000) {
                var found: Attachment? = null
                while (found == null) {
                    found = connected.call(Api.History, HistoryParams("media", id, 100)).map { it.entry }.filterIsInstance<SessionEntry.ToolResult>().flatMap { it.attachments }.singleOrNull()
                    if (found == null) delay(10)
                }
                found
            }
            assertEquals("image/png", attachment.mimeType)
            assertEquals("Harness-output.png", attachment.name)
            assertEquals(image, connected.call(Api.Attachment, AttachmentRef(attachment.id)).base64)
            connected.close(); server.stop()
            val restored = Harness(home = Path(dir.path))
            assertEquals(attachment, restored.store.entries("media", id).filterIsInstance<SessionEntry.ToolResult>().single().attachments.single())
            assertEquals(image, restored.attachments.download(attachment.id).base64)
        } finally { client?.close(); server.stop(); dir.deleteRecursively() }
    }

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
            val browserError = assertFails { first.call(Api.BrowserTabs, BrowserOwner("external")) }
            assertTrue(browserError.message.orEmpty().contains("managed by this Agent's harness"))
            val screensError = assertFails { first.call(Api.Screens, BrowserOwner("external")) }
            assertTrue(screensError.message.orEmpty().contains("managed by this Agent's harness"))
            first.call(Api.Send, SendParams("external", id, "wait"))
            first.close()
            val second = KinuvoClient("ws://127.0.0.1:$port/ws", "manager", token = token)
            client = second
            second.connect()
            assertEquals(ExecutionPhase.Running, second.call(Api.ExecutionSnapshot, ExecutionRef(id)).phase)
            second.call(Api.CatchUp, CatchUpParams("external", id))
            assertEquals("wait", second.call(Api.History, HistoryParams("external", id, 100)).map { it.entry }.filterIsInstance<SessionEntry.UserMessage>().single().text)
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
            val answer = ExecutionAnswer(id, approval.id, kotlinx.serialization.json.Json.parseToJsonElement("""{"optionId":"yes"}"""))
            assertTrue(responder.call(Api.ExecutionAnswer, answer))
            assertFalse(responder.call(Api.ExecutionAnswer, answer))
            withTimeout(5000) { while(responder.call(Api.ExecutionSnapshot, ExecutionRef(id)).phase != ExecutionPhase.Idle) delay(10) }
            responder.call(Api.Send, SendParams("external", id, "question"))
            withTimeout(5000) { while(responder.call(Api.ExecutionSnapshot, ExecutionRef(id)).pending.isEmpty()) delay(10) }
            val question = responder.call(Api.ExecutionSnapshot, ExecutionRef(id)).pending.single()
            assertEquals(ExecutionRequestKind.Question, question.kind)
            assertTrue(question.payload.toString().contains("https://example.test/sign-in"))
            assertTrue(responder.call(Api.ExecutionAnswer, ExecutionAnswer(id, question.id, kotlinx.serialization.json.Json.parseToJsonElement("""{"opened":false}"""))))
            withTimeout(5000) { while(responder.call(Api.ExecutionSnapshot, ExecutionRef(id)).phase != ExecutionPhase.Idle) delay(10) }
            responder.close()
            server.stop()
            server = HarnessServer(Harness(home = Path(dir.path)))
            val restartedPort = server.start("127.0.0.1", 0, schedule = false)
            val third = KinuvoClient("ws://127.0.0.1:$restartedPort/ws", "manager", token = token)
            client = third
            third.connect()
            assertEquals("external", third.call(Api.ExecutionCatalog, Unit).agents.single { !it.archived }.id)
            assertEquals(ExecutionPhase.Lost, third.call(Api.ExecutionSnapshot, ExecutionRef(id)).phase)
            assertEquals("external", third.call(Api.ExecutionSnapshot, ExecutionRef(id)).externalSessionId)
            val saved = third.call(Api.ExecutionSnapshot, ExecutionRef(id)).messages
            val resumed = third.call(Api.ResumeExecution, ExecutionRef(id))
            assertEquals(id, resumed.sessionId)
            assertEquals(saved, resumed.messages.take(saved.size))
            assertTrue(resumed.messages.any { it.role == "notice" && it.text.contains("disconnected") })
            assertEquals("external", resumed.externalSessionId)
            third.call(Api.CloseExecution, ExecutionRef(id))
            third.call(Api.UnregisterExternalAgent, ExecutionRef("external"))
            third.call(Api.CloseExecution, ExecutionRef(id))
            assertEquals(resumed.messages, third.call(Api.ExecutionSnapshot, ExecutionRef(id)).messages)
        } finally { client?.close(); server.stop(); dir.deleteRecursively() }
    }
}
