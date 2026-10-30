package kinuvo.harness.server

import kinuvo.harness.testing.scriptedModel
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.core.PrivateFile
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.flowOf
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class RemoteServerTest {
    @Test fun scopedRemoteExecution() = runBlocking {
        val dir = Files.createTempDirectory("kinuvo-remote-test").toFile()
        val script = dir.resolve("agent.py")
        script.writeText("""
            import sys,json
            def emit(v): print(json.dumps(v),flush=True)
            for line in sys.stdin:
                r=json.loads(line); m=r.get('method')
                if m=='initialize': emit({'jsonrpc':'2.0','id':r['id'],'result':{'protocolVersion':r['params']['protocolVersion'],'info':{'name':'mock','version':'1'},'capabilities':{}}})
                elif m=='session/new': emit({'jsonrpc':'2.0','id':r['id'],'result':{'sessionId':'external'}})
                elif m=='session/prompt':
                    emit({'jsonrpc':'2.0','id':r['id'],'result':{}})
                    text=r['params']['prompt'][0]['text']
                    if text=='permission': emit({'jsonrpc':'2.0','id':'p','method':'session/request_permission','params':{'sessionId':'external','title':'Run?','options':[{'optionId':'yes','name':'Allow','kind':'allow_once'}]}})
                    if text=='question': emit({'jsonrpc':'2.0','id':'q','method':'elicitation/create','params':{'sessionId':'external','message':'Name?','mode':'form','requestedSchema':{'type':'object','properties':{}}}})
                elif r.get('id') in ['p','q']: emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'external','update':{'sessionUpdate':'state_update','state':'idle','stopReason':'end_turn'}}})
                elif m=='session/cancel': emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'external','update':{'sessionUpdate':'state_update','state':'idle','stopReason':'cancelled'}}})
        """.trimIndent())
        val source = HarnessServer(Harness(home = Path(dir.resolve("source").path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(dir.path, "none.json"))))), modelFactory = { scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("remote native reply"), StreamFrame.End()) } }))
        val local = HarnessServer(Harness(home = Path(dir.resolve("local").path)))
        val clients = mutableListOf<KinuvoClient>()
        suspend fun manager(server: HarnessServer, port: Int): KinuvoClient = KinuvoClient("ws://127.0.0.1:$port/ws", "manager", pairingCode = server.auth.pairing(false, true)!!.code).also { clients.add(it); it.connect() }
        try {
            val sourcePort = source.start("127.0.0.1", 0, schedule = false)
            val localPort = local.start("127.0.0.1", 0, schedule = false)
            val origin = manager(source, sourcePort)
            var destination = manager(local, localPort)
            origin.call(Api.SaveHarnessConnection, HarnessConnection("acp", "ACP", ExecutionConnection.Acp(listOf("/usr/bin/env", "python3", script.path))))
            for (id in listOf("exposed", "private")) origin.call(Api.RegisterExternalAgent, AgentRegistration(id, id, "acp", cwd = dir.path))
            val nativeToken = origin.call(Api.GrantExecution, ExposureGrant("native", setOf("default")))
            destination.call(Api.SaveRemoteLink, SaveRemoteLink("native-source", "ws://127.0.0.1:$sourcePort/ws", nativeToken))
            destination.call(Api.SaveHarnessConnection, HarnessConnection("native-remote", "Native source", ExecutionConnection.Remote("native-source")))
            destination.call(Api.RegisterExternalAgent, AgentRegistration("native-alias", "Native local alias", "native-remote", remoteAgentId = "default"))
            val nativeId = destination.call(Api.CreateSession, AgentRef("native-alias")).id
            destination.call(Api.Send, SendParams("native-alias", nativeId, "hello native"))
            withTimeout(10000) { while (destination.call(Api.ExecutionSnapshot, ExecutionRef(nativeId)).messages.none { it.text == "remote native reply" }) delay(25) }
            val token = origin.call(Api.GrantExecution, ExposureGrant("link", setOf("exposed")))
            destination.call(Api.SaveRemoteLink, SaveRemoteLink("source", "ws://127.0.0.1:$sourcePort/ws", token))
            destination.call(Api.SaveHarnessConnection, HarnessConnection("remote", "Source", ExecutionConnection.Remote("source")))
            assertEquals(listOf("exposed"), destination.call(Api.DiscoverRemote, ExecutionRef("source")).agents.map { it.id })
            val scoped = KinuvoClient("ws://127.0.0.1:$sourcePort/ws", "linked", token = token).also { clients.add(it); it.connect() }
            assertFails { scoped.call(Api.Agents, Unit) }
            assertFails { scoped.call(Api.RemoteExecution, RemoteExecutionCall("private", action = "create")) }
            destination.call(Api.RegisterExternalAgent, AgentRegistration("alias", "Local alias", "remote", remoteAgentId = "exposed"))
            val id = destination.call(Api.CreateSession, AgentRef("alias")).id
            val initial = destination.call(Api.ExecutionSnapshot, ExecutionRef(id))
            assertNotEquals(id, initial.externalSessionId)
            suspend fun snapshot() = destination.call(Api.ExecutionSnapshot, ExecutionRef(id))
            suspend fun waitFor(predicate: (ExecutionSnapshot) -> Boolean) { withTimeout(10000) { while (!predicate(snapshot())) delay(25) } }
            destination.call(Api.Send, SendParams("alias", id, "wait"))
            waitFor { it.phase == ExecutionPhase.Running && it.messages.any { m -> m.text == "wait" } }
            destination.call(Api.Stop, StopParams(id))
            waitFor { it.phase == ExecutionPhase.Idle }
            destination.call(Api.Send, SendParams("alias", id, "permission"))
            waitFor { it.pending.isNotEmpty() }
            val reconnectToken = requireNotNull(destination.lastConnect).token
            destination.close()
            destination = KinuvoClient("ws://127.0.0.1:$localPort/ws", "manager", token = reconnectToken).also { clients.add(it); it.connect() }
            val approval = snapshot().pending.single()
            val otherToken = origin.call(Api.GrantExecution, ExposureGrant("other-link", setOf("exposed")))
            val other = KinuvoClient("ws://127.0.0.1:$sourcePort/ws", "other-linked", token = otherToken).also { clients.add(it); it.connect() }
            val otherSession = requireNotNull(other.call(Api.RemoteExecution, RemoteExecutionCall("exposed", action = "create")).snapshot).sessionId
            val sourceSession = requireNotNull(snapshot().externalSessionId)
            assertFails { other.call(Api.RemoteExecution, RemoteExecutionCall("exposed", sourceSession, "snapshot")) }
            assertFails { other.call(Api.RemoteExecution, RemoteExecutionCall("exposed", sourceSession, "answer", requestId = approval.id, answer = kotlinx.serialization.json.JsonNull)) }
            assertFails { scoped.call(Api.RemoteExecution, RemoteExecutionCall("exposed", otherSession, "snapshot")) }
            assertFails { scoped.call(Api.RemoteExecution, RemoteExecutionCall("exposed", otherSession, "cancel")) }
            assertEquals("Allow", approval.options.single().label)
            val answer = ExecutionAnswer(id, approval.id, kotlinx.serialization.json.Json.parseToJsonElement("""{"optionId":"yes"}"""))
            assertFails { destination.call(Api.ExecutionAnswer, answer.copy(answer = kotlinx.serialization.json.Json.parseToJsonElement("""{"optionId":"not-offered"}"""))) }
            assertTrue(destination.call(Api.ExecutionAnswer, answer))
            assertFalse(destination.call(Api.ExecutionAnswer, answer))
            waitFor { it.phase == ExecutionPhase.Idle }
            destination.call(Api.Send, SendParams("alias", id, "question"))
            waitFor { it.pending.isNotEmpty() }
            val question = snapshot().pending.single()
            assertTrue(destination.call(Api.ExecutionAnswer, ExecutionAnswer(id, question.id, kotlinx.serialization.json.JsonNull)))
            waitFor { it.phase == ExecutionPhase.Idle }
            assertTrue(destination.call(Api.History, HistoryParams("alias", id, 100)).isNotEmpty())
            val nativeHistory = destination.call(Api.History, HistoryParams("native-alias", nativeId, 100)).map { it.entry }
            assertEquals(listOf("remote native reply"), nativeHistory.filterIsInstance<SessionEntry.AssistantMessage>().map { it.text })
            assertEquals(listOf("hello native"), nativeHistory.filterIsInstance<SessionEntry.UserMessage>().map { it.text })
            origin.call(Api.RevokeExecution, ExecutionRef("link"))
            waitFor { it.phase == ExecutionPhase.Lost }
        } finally { clients.forEach { it.close() }; local.stop(); source.stop(); dir.deleteRecursively() }
    }
}
