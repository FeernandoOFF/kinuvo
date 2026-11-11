package kinuvo.gateways.mcp

import kinuvo.harness.acp.AcpExecutionFactory
import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentRef
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.testing.server.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.*

/** A real subprocess speaks ACP and calls the real Server's HTTP endpoint. */
class McpAcpTest {
    @Test fun twoTurnsRenewCredentialsAndCancelThenResume() = runBlocking {
        val directory = Files.createTempDirectory("kinuvo-mcp-acp")
        val script = directory.resolve("agent.py")
        Files.writeString(script, """
            import sys,json,threading,urllib.request,urllib.error,os
            lock=threading.Lock()
            current=None
            old=None
            prompts={}
            cancelled=threading.Event()
            def emit(v):
                with lock: print(json.dumps(v),flush=True)
            def reply(r,v): emit({'jsonrpc':'2.0','id':r['id'],'result':v})
            def update(v): emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'external','update':v}})
            def http(server,method,name='deploy',arguments=None):
                req=urllib.request.Request(server['url'],data=json.dumps({'jsonrpc':'2.0','id':1,'method':method,'params':{'name':name,'arguments':arguments or {}}}).encode(),headers={h['name']:h['value'] for h in server['headers']})
                try:
                    with urllib.request.urlopen(req,timeout=15) as res: return res.status,json.load(res)
                except urllib.error.HTTPError as e: return e.code,None
            def turn(r,server):
                questions=r['params']['prompt'][0]['text']=='questions'
                status,result=http(server,'tools/call','ask_user',{'questions':[{'id':'answer','label':'Your answer','kind':'text'}]}) if questions else http(server,'tools/call')
                if cancelled.is_set(): return
                assert status==200 and result['result']['isError']==False, (status,result)
                update({'sessionUpdate':'agent_message_chunk','messageId':str(r['id']),'content':{'type':'text','text':'deployed'}})
                update({'sessionUpdate':'state_update','state':'idle','stopReason':'end_turn'})
            for line in sys.stdin:
                r=json.loads(line); m=r.get('method')
                if m=='initialize': reply(r,{'protocolVersion':r['params']['protocolVersion'],'info':{'name':'mcp-fake','version':'1'},'capabilities':{'session':{'mcp':{'http':{}},'prompt':{}}}})
                elif m in ('session/new','session/resume'):
                    servers=r['params'].get('mcpServers',[])
                    assert len(servers)==1, servers
                    current=servers[0]
                    if old:
                        assert old['headers']!=current['headers']
                        assert http(old,'tools/list')[0]==401
                    old=current
                    reply(r,{'sessionId':'external'} if m=='session/new' else {})
                elif m=='session/prompt':
                    text=r['params']['prompt'][0]['text']
                    if text=='disconnect': os._exit(0)
                    if text=='private':
                        reply(r,{})
                        emit({'jsonrpc':'2.0','id':'private-question','method':'session/request_permission','params':{'sessionId':'external','title':'Private tool','toolCall':{'rawInput':{'serverName':'private','toolName':'deploy'}},'options':[{'optionId':'yes','name':'Allow','kind':'allow_once'}]}})
                        continue
                    cancelled.clear()
                    reply(r,{'messageId':str(r['id'])})
                    key='kinuvo-permission-'+str(r['id'])
                    prompts[key]=(r,current)
                    emit({'jsonrpc':'2.0','id':key,'method':'session/request_permission','params':{'sessionId':'external','title':'Kinuvo tool','toolCall':{'rawInput':{'serverName':'kinuvo','toolName':'deploy'}},'options':[{'optionId':'yes','name':'Allow','kind':'allow_once'}]}})
                elif str(r.get('id','')).startswith('kinuvo-permission-'):
                    assert r['result']['outcome']['optionId']=='yes'
                    prompt,server=prompts.pop(r['id'])
                    threading.Thread(target=turn,args=(prompt,server),daemon=True).start()
                elif r.get('id')=='private-question':
                    assert r['result']['outcome']['optionId']=='yes'
                    update({'sessionUpdate':'state_update','state':'idle','stopReason':'end_turn'})
                elif m=='session/cancel':
                    cancelled.set()
                    assert http(current,'tools/list')[0]==401
                    update({'sessionUpdate':'state_update','state':'idle','stopReason':'cancelled'})
        """.trimIndent())
        val access = McpAccess()
        val listener = McpListener(access)
        listener.start()
        val server = TestServer.start(
            plugins = listOf(FakeTools(FakeTool("deploy", Risk.High) { "deployed" })),
            executionFactory = { harness -> McpExecutionFactory(AcpExecutionFactory(), harness, access, { listener.url }) },
        )
        try {
            val client = server.client(token = server.auth.sessionToken()).also { it.connect() }
            client.call(Api.SaveHarnessConnection, HarnessConnection("acp", "ACP", ExecutionConnection.Acp(listOf("/usr/bin/env", "python3", script.toString()))))
            val agent = AgentId("external")
            client.call(Api.RegisterExternalAgent, AgentRegistration(agent, "External", "acp", cwd = directory.toString()))
            val session = client.call(Api.CreateSession, AgentRef(agent))
            client.call(Api.CatchUp, CatchUpParams(agent, session.id))
            suspend fun phase(expected: ExecutionPhase) = withTimeout(10000) {
                while (client.call(Api.ExecutionSnapshot, ExecutionRef(session.id.value)).phase != expected) delay(10)
            }
            suspend fun pending(): PendingInteraction.Approval = withTimeout(10000) {
                while (true) {
                    server.harness.log.pending(session.id).filterIsInstance<PendingInteraction.Approval>().firstOrNull()?.let { return@withTimeout it }
                    delay(10)
                }
                error("unreachable")
            }
            repeat(2) {
                client.call(Api.Send, SendParams(agent, session.id, "deploy"))
                val approval = pending()
                val answer = if (it == 0) InteractionAnswer.Approval(ApprovalDecision.AllowOnce)
                    else InteractionAnswer.Harness(buildJsonObject { put("optionId", "allow_once") })
                assertTrue(client.call(Api.Respond, RespondParams(session.id, approval.id, answer)))
                phase(ExecutionPhase.Idle)
            }
            client.call(Api.Send, SendParams(agent, session.id, "questions"))
            val questions = withTimeout(10000) {
                while (true) {
                    server.harness.log.pending(session.id).filterIsInstance<PendingInteraction.Questions>().firstOrNull()?.let { return@withTimeout it }
                    delay(10)
                }
                error("unreachable")
            }
            assertTrue(client.call(Api.Respond, RespondParams(session.id, questions.id, InteractionAnswer.Questions(mapOf("answer" to listOf("yes"))))))
            phase(ExecutionPhase.Idle)
            assertTrue(server.harness.log.pending(session.id).isEmpty())
            client.call(Api.Send, SendParams(agent, session.id, "private"))
            val privateQuestion = withTimeout(10000) {
                while (true) {
                    client.call(Api.ExecutionSnapshot, ExecutionRef(session.id.value)).pending.filterIsInstance<PendingInteraction.HarnessApproval>().firstOrNull()?.let { return@withTimeout it }
                    delay(10)
                }
                error("unreachable")
            }
            assertTrue(client.call(Api.Respond, RespondParams(session.id, privateQuestion.id, InteractionAnswer.Harness(buildJsonObject { put("optionId", "yes") }))))
            phase(ExecutionPhase.Idle)
            val history = client.call(Api.History, HistoryParams(agent, session.id, 100)).map { it.entry }
            assertEquals(2, history.filterIsInstance<SessionEntry.ToolResult>().count { it.output == "deployed" })
            client.call(Api.Send, SendParams(agent, session.id, "cancel"))
            val cancelled = pending()
            client.call(Api.Stop, StopParams(session.id))
            phase(ExecutionPhase.Idle)
            assertTrue(server.harness.log.pending(session.id).none { it.id == cancelled.id })
            assertTrue(client.call(Api.ExecutionSnapshot, ExecutionRef(session.id.value)).pending.none { it.id == cancelled.id })
            assertFalse(server.harness.approvals.respond(cancelled.id, ApprovalDecision.AllowOnce))
            client.call(Api.Send, SendParams(agent, session.id, "disconnect"))
            phase(ExecutionPhase.Lost)
            client.call(Api.ResumeExecution, ExecutionRef(session.id.value))
            client.call(Api.Send, SendParams(agent, session.id, "resume"))
            val approval = pending()
            assertTrue(client.call(Api.Respond, RespondParams(session.id, approval.id, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
            phase(ExecutionPhase.Idle)
        } finally {
            server.stop(); listener.stop()
            Files.deleteIfExists(script); Files.deleteIfExists(directory)
        }
    }
}
