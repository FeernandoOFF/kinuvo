package kinuvo.gateways.mcp

import kinuvo.harness.Agent
import kinuvo.harness.AgentDoc
import kinuvo.harness.acp.AcpExecutionFactory
import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentRef
import kinuvo.protocol.agent.UpdateAgentParams
import kinuvo.protocol.approval.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.plugin.PluginDefinition
import kinuvo.server.plugin.*
import kinuvo.server.execution.serverToolAgent
import kotlinx.serialization.json.JsonObject
import kinuvo.protocol.session.*
import kinuvo.testing.server.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.*

/** A real subprocess speaks ACP and calls the real Server's HTTP endpoint. */
class McpAcpTest {
    @Test fun twoTurnsReuseCredentialsAndCancelThenResume() = runBlocking {
        val directory = Files.createTempDirectory("kinuvo-mcp-acp")
        val script = directory.resolve("agent.py")
        Files.writeString(script, """
            import sys,json,threading,urllib.request,urllib.error,os
            lock=threading.Lock()
            current=None
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
                if not questions:
                    assert result['result']['content']==[
                        {'type':'text','text':'deployed'},
                        {'type':'image','data':'iVBORw0KGgo=','mimeType':'image/png'},
                        {'type':'text','text':'Attachment: report.txt (text/plain)'}
                    ], result
                update({'sessionUpdate':'agent_message_chunk','messageId':str(r['id']),'content':{'type':'text','text':'deployed'}})
                update({'sessionUpdate':'state_update','state':'idle','stopReason':'end_turn'})
            for line in sys.stdin:
                r=json.loads(line); m=r.get('method')
                if m=='initialize': reply(r,{'protocolVersion':r['params']['protocolVersion'],'info':{'name':'mcp-fake','version':'1'},'capabilities':{'session':{'mcp':{'http':{}},'prompt':{}}}})
                elif m in ('session/new','session/resume'):
                    servers=r['params'].get('mcpServers',[])
                    assert len(servers)==1, servers
                    assert current is None, "Already loaded"
                    current=servers[0]
                    status,initialized=http(current,'initialize')
                    instructions=initialized['result']['instructions']
                    assert status==200 and 'Server platform:' in instructions, initialized
                    assert 'IDENTITY_SENTINEL' in instructions and 'SOUL_SENTINEL' in instructions, instructions
                    assert 'MEMORY_SENTINEL' not in instructions and 'USER_NOTES_SENTINEL' not in instructions, instructions
                    assert 'PLUGIN_SENTINEL' in instructions and 'Current Skills:' in instructions, instructions
                    reply(r,{'sessionId':'external'} if m=='session/new' else {})
                elif m=='session/prompt':
                    text=r['params']['prompt'][0]['text']
                    if text=='disconnect':
                        with open('previous-mcp.json','w') as f: json.dump(current,f)
                        os._exit(0)
                    if text=='resume':
                        with open('previous-mcp.json') as f: previous=json.load(f)
                        assert http(previous,'tools/list')[0]==401
                    if text.startswith('private'):
                        reply(r,{})
                        emit({'jsonrpc':'2.0','id':'private-question','method':'session/request_permission','params':{'sessionId':'external','title':'Private tool','toolCall':{'rawInput':{'serverName':'private','toolName':'deploy'}},'options':([{'optionId':'yes','name':'Always allow','kind':'allow_always'}] if text.endswith('always') else [
                            {'optionId':'always','name':'Always allow','kind':'allow_always'},
                            {'optionId':'yes','name':'Allow','kind':'allow_once'}
                        ])}})
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
                    status,result=http(current,'tools/call')
                    assert status==200 and result['error']['message']=='No turn is active'
                    update({'sessionUpdate':'state_update','state':'idle','stopReason':'cancelled'})
        """.trimIndent())
        val access = McpAccess()
        val listener = McpListener(access)
        listener.start()
        val server = TestServer.start(
            plugins = listOf(object : ServerPlugin {
                override val definition = PluginDefinition("test.tools", "Test tools")
                override suspend fun instructions(agent: Agent) = InstructionSection("Test tools", "PLUGIN_SENTINEL")
                override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(object : Tool {
                    override val descriptor = toolSpec("deploy", "Fake deploy")
                    override val risk = Risk.High
                    override suspend fun execute(args: JsonObject) = "deployed"
                    override suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String {
                        run.attach("screen.png", byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
                        run.attach("report.txt", "report".encodeToByteArray())
                        return execute(args)
                    }
                }))
            }),
            executionFactory = { harness -> McpExecutionFactory(AcpExecutionFactory(autoApprove = { registration, session, kind ->
                harness.composer.autoApproveHarness(registration.serverToolAgent(), session, kind)
            }), harness, access, { listener.url }) },
        )
        try {
            val client = server.client(token = server.auth.sessionToken()).also { it.connect() }
            client.call(Api.SaveHarnessConnection, HarnessConnection("acp", "ACP", ExecutionConnection.Acp(listOf("/usr/bin/env", "python3", script.toString()))))
            val agent = AgentId("external")
            client.call(Api.RegisterExternalAgent, AgentRegistration(agent, "External", "acp", cwd = directory.toString()))
            client.call(Api.UpdateAgent, UpdateAgentParams(agent, identity = "IDENTITY_SENTINEL", soul = "SOUL_SENTINEL"))
            val details = client.call(Api.Agent, AgentRef(agent))
            assertEquals("IDENTITY_SENTINEL", details.identity)
            assertEquals("SOUL_SENTINEL", details.soul)
            server.harness.docs.write(agent, AgentDoc.Memory, "MEMORY_SENTINEL")
            server.harness.docs.write(agent, AgentDoc.UserNotes, "USER_NOTES_SENTINEL")
            val session = client.call(Api.CreateSession, AgentRef(agent))
            val tools = client.call(Api.Capabilities, CapabilitiesParams(agent, session.id)).tools
            val deploy = tools.single { it.id == "deploy" }
            assertEquals(Support.Supported, deploy.state.support)
            client.call(Api.SaveServerRules, TrustRules(deny = DenyRules(tools = listOf(requireNotNull(deploy.identity).key))))
            assertEquals(Authorization.Denied, client.call(Api.Capabilities, CapabilitiesParams(agent, session.id)).tools.single { it.id == "deploy" }.state.authorization)
            client.call(Api.SaveServerRules, TrustRules())
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
            client.call(Api.ConfigureSession, ConfigureSessionParams(agent, session.id, SessionSettings(approvals = "full")))
            for (prompt in listOf("private", "private always")) {
                client.call(Api.Send, SendParams(agent, session.id, prompt))
                phase(ExecutionPhase.Idle)
            }
            client.call(Api.ConfigureSession, ConfigureSessionParams(agent, session.id, SessionSettings()))
            val history = client.call(Api.History, HistoryParams(agent, session.id, 100)).map { it.entry }
            val results = history.filterIsInstance<SessionEntry.ToolResult>().filter { it.output == "deployed" }
            assertEquals(2, results.size)
            results.forEach { assertEquals(listOf("screen.png", "report.txt"), it.attachments.map { attachment -> attachment.name }) }
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
            Files.deleteIfExists(directory.resolve("previous-mcp.json")); Files.deleteIfExists(script); Files.deleteIfExists(directory)
        }
    }
}
