package kinuvo.harness.server

import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.client.KinuvoClient
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.AgentModel
import kinuvo.harness.core.agenttools.CallerScope
import kinuvo.harness.core.plugin.HarnessPlugin
import kinuvo.harness.core.plugin.ModelProvider
import kinuvo.harness.server.reflection.Reflector
import kinuvo.harness.testing.scriptedModel
import kinuvo.harness.testing.toolResults
import kinuvo.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DelegationServerTest {
    private val dir: File = Files.createTempDirectory("kinuvo-delegation").toFile()
    private val advertised = mutableMapOf<String, List<String>>()

    private fun provider(name: String, local: Boolean) = object : HarnessPlugin, ModelProvider {
        override val definition = PluginDefinition("test.$name", "Test")
        override val id = name
        override val modelProviders get() = listOf(this)
        override fun onDevice(model: String) = local
        override fun model(config: AgentConfig): AgentModel = scriptedModel { prompt, tools ->
            advertised["${config.provider}/${config.model}"] = tools.map { it.name }
            if (config.model == "slow") flow { awaitCancellation() } else if (config.model == "boss") {
                val results = prompt.messages.toolResults
                if (results.isEmpty()) flowOf(StreamFrame.ToolCallComplete("c1", "delegate", """{"agent":"worker","goal":"from a native run"}"""), StreamFrame.End())
                else flowOf(StreamFrame.TextDelta("boss got: ${results.single().output}"), StreamFrame.End())
            } else {
                val text = prompt.messages.filterIsInstance<Message.User>().last().textContent()
                flowOf(StreamFrame.TextDelta("${config.model}: $text"), StreamFrame.End())
            }
        }
    }

    private val harness = Harness(Path(dir.path), plugins = listOf(provider("cloud", false), provider("local", true)))
    private val server = HarnessServer(harness)
    private val endpoint get() = server.runtime.mcpEndpoint

    private suspend fun agent(id: String, provider: String, model: String) {
        if (harness.store.agents().none { it.id == id }) harness.store.createAgent(id)
        Admin(harness, Runs(harness.log, CoroutineScope(Dispatchers.Default))) {}.update(UpdateAgentParams(id, provider = provider, model = model))
    }

    private suspend fun session(agentId: String): CallerScope {
        val agent = harness.store.agents().first { it.id == agentId }
        val id = harness.newSession(agent).id
        harness.configure(agent, id, SessionSettings(approvals = "full"))
        return CallerScope(agentId, id)
    }

    private fun obj(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) -> when (v) { is JsonElement -> put(k, v); is Boolean -> put(k, v); else -> put(k, v.toString()) } }
    }

    private suspend fun rpc(scope: CallerScope, method: String, params: JsonObject): JsonObject {
        val token = server.runtime.mcp.grant(scope.agentId, scope.sessionId!!)!!.token
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }.toString()
        return Json.parseToJsonElement(endpoint.handle("Bearer $token", body).body!!).jsonObject["result"]!!.jsonObject
    }

    /** The reply's text, and whether it was an error. */
    private suspend fun call(scope: CallerScope, name: String, vararg args: Pair<String, Any>): Pair<Boolean, String> {
        val result = rpc(scope, "tools/call", buildJsonObject { put("name", name); put("arguments", obj(*args)) })
        return result["isError"]!!.jsonPrimitive.boolean to result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
    }

    private suspend fun until(what: String = "condition", check: suspend () -> Boolean) = withTimeout(10_000) { while (!check()) delay(20) }

    private suspend fun children(agentId: String) = harness.store.sessions(agentId).filter { it.parent != null }

    private suspend fun running(sessionId: String) = harness.log.status(sessionId) != SessionStatus.Idle

    private val port get() = server.runtime.mcp.url!!.substringAfterLast(':').substringBefore('/')

    /** Lets calls from [scope]'s Session run without asking, as an Agent set to full tolerance would. */
    private suspend fun CallerScope.trusted(): CallerScope {
        val agent = harness.store.agents().first { it.id == agentId }
        harness.configure(agent, sessionId!!, SessionSettings(approvals = "full"))
        return this
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = try {
        runBlocking {
            server.start("127.0.0.1", 0, schedule = false)
            agent("default", "cloud", "echo"); agent("worker", "cloud", "echo"); agent("sleeper", "cloud", "slow"); agent("private", "local", "small")
            block()
        }
    } finally { runBlocking { server.stop() }; dir.deleteRecursively() }

    @Test fun nativeToNativeForegroundReturnsTheChildsAnswerAndTheChildNeverSeesTheParentsTranscript() = test {
        val parent = session("default")
        harness.store.append("default", parent.sessionId!!, SessionEntry.UserMessage("TOP SECRET parent chat"))
        val (error, text) = call(parent, "delegate", "agent" to "worker", "goal" to "summarise", "context" to "in one line")
        assertFalse(error, text)
        val child = children("worker").single()
        assertEquals("echo: summarise\n\nContext:\nin one line\n\n(Session ${child.id} on worker)", text)
        assertEquals(SessionParent("default", parent.sessionId!!, 1), child.parent)
        val transcript = harness.store.entries("worker", child.id).filterIsInstance<SessionEntry.UserMessage>()
        assertEquals(listOf("summarise\n\nContext:\nin one line"), transcript.map { it.text })
        assertFalse(child.hidden)
    }

    @Test fun aNativeRunDelegatesWithItsOwnTools() = test {
        agent("boss", "cloud", "boss")
        val bossAgent = harness.store.agents().first { it.id == "boss" }
        val id = harness.newSession(bossAgent).id
        val client = KinuvoClient("ws://127.0.0.1:$port/ws", "manager", token = server.auth.sessionToken())
        try {
            client.connect()
            client.call(Api.Send, SendParams("boss", id, "go"))
            until { harness.log.live(id).approval != null }
            val approval = harness.log.live(id).approval!!
            assertEquals(Risk.Medium, approval.risk)
            harness.approvals.respond(approval.approvalId, ApprovalDecision.AllowOnce)
            until { harness.store.entries("boss", id).any { it is SessionEntry.AssistantMessage } }
        } finally { client.close() }
        val child = children("worker").single()
        assertEquals(SessionParent("boss", id, 1, toolCallId = "c1"), child.parent)
        val answer = harness.store.entries("boss", id).filterIsInstance<SessionEntry.AssistantMessage>().single().text
        assertEquals("boss got: echo: from a native run\n\n(Session ${child.id} on worker)", answer)
    }

    @Test fun backgroundReturnsAtOnceAndDelegateWaitFetchesTheAnswer() = test {
        val parent = session("default")
        val (error, started) = call(parent, "delegate", "agent" to "worker", "goal" to "later", "background" to true)
        assertFalse(error, started)
        val child = children("worker").single()
        assertTrue(child.id in started)
        val (_, waited) = call(parent, "delegate_wait", "timeout_seconds" to 30)
        assertEquals("Session ${child.id}:\necho: later", waited)
    }

    @Test fun aBatchRunsEveryTask() = test {
        val parent = session("default")
        val tasks = JsonArray(listOf(obj("agent" to "worker", "goal" to "one"), obj("agent" to "default", "goal" to "two")))
        val (error, text) = call(parent, "delegate", "tasks" to tasks)
        assertFalse(error, text)
        assertTrue("Task 1 (worker): echo: one" in text && "Task 2 (default): echo: two" in text)
    }

    @Test fun anExternalAgentTakesDelegationsThroughTheSameDispatch() = test {
        val script = dir.resolve("agent.py")
        script.writeText("""
            import sys,json
            def emit(v): print(json.dumps(v),flush=True)
            for line in sys.stdin:
                r=json.loads(line); m=r.get('method')
                if m=='initialize': emit({'jsonrpc':'2.0','id':r['id'],'result':{'protocolVersion':r['params']['protocolVersion'],'info':{'name':'mock','version':'1'},'capabilities':{'session':{}}}})
                elif m=='session/new': emit({'jsonrpc':'2.0','id':r['id'],'result':{'sessionId':'external'}})
                elif m=='session/prompt':
                    text=r['params']['prompt'][0]['text']
                    emit({'jsonrpc':'2.0','id':r['id'],'result':{'messageId':'accepted'}})
                    emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'external','update':{'sessionUpdate':'agent_message_chunk','messageId':'m','content':{'type':'text','text':'external got: '+text}}}})
                    emit({'jsonrpc':'2.0','method':'session/update','params':{'sessionId':'external','update':{'sessionUpdate':'state_update','state':'idle','stopReason':'end_turn'}}})
        """.trimIndent())
        val client = KinuvoClient("ws://127.0.0.1:$port/ws", "manager", token = server.auth.sessionToken())
        try {
            client.connect()
            client.call(Api.SaveHarnessConnection, HarnessConnection("mock", "Mock", ExecutionConnection.Acp(listOf("/usr/bin/env", "python3", script.path))))
            client.call(Api.RegisterExternalAgent, AgentRegistration("external", "External", "mock", cwd = dir.path))
            val parent = session("default")
            val listed = call(parent, "agents_list").second
            assertTrue("external" in listed)
            val (error, text) = call(parent, "delegate", "agent" to "external", "goal" to "ping")
            assertFalse(error, text)
            val child = children("external").single()
            assertEquals("external got: ping\n\n(Session ${child.id} on external)", text)
            assertEquals(parent.sessionId, child.parent?.sessionId)
            assertTrue(call(parent, "delegate", "agent" to "external", "goal" to "x", "hidden" to true).first)
            assertTrue(call(parent, "delegate", "agent" to "external", "goal" to "x", "model" to "gpt").first)
        } finally { client.close() }
    }

    @Test fun childrenAtTheDepthLimitGetNoDelegationTools() = test {
        val root = session("default")
        val first = harness.store.createSession("worker", "").id.also { harness.store.append("worker", it, SessionEntry.Details(parent = SessionParent("default", root.sessionId!!, 1))) }
        val second = harness.store.createSession("worker", "").id.also { harness.store.append("worker", it, SessionEntry.Details(parent = SessionParent("worker", first, 2))) }
        fun names(result: JsonObject) = result["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue("delegate" in names(rpc(root, "tools/list", JsonObject(emptyMap()))))
        assertTrue("delegate" in names(rpc(CallerScope("worker", first), "tools/list", JsonObject(emptyMap()))))
        val deepest = names(rpc(CallerScope("worker", second), "tools/list", JsonObject(emptyMap())))
        assertTrue("sessions_list" in deepest)
        assertTrue(deepest.none { it in setOf("delegate", "delegate_wait", "subagents") })
        assertTrue(call(CallerScope("worker", second), "delegate", "goal" to "x").first)
    }

    @Test fun stoppingASessionStopsItsRunningDescendants() = test {
        val parent = session("default")
        call(parent, "delegate", "agent" to "sleeper", "goal" to "wait", "background" to true)
        val child = children("sleeper").single()
        call(CallerScope("sleeper", child.id).trusted(), "delegate", "agent" to "sleeper", "goal" to "wait more", "background" to true)
        val grandchild = children("sleeper").single { it.id != child.id }
        assertEquals(2, grandchild.parent?.depth)
        until { running(child.id) && running(grandchild.id) }
        val client = KinuvoClient("ws://127.0.0.1:$port/ws", "manager", token = server.auth.sessionToken())
        try {
            client.connect()
            client.call(Api.Stop, StopParams(parent.sessionId!!))
        } finally { client.close() }
        until { !running(child.id) && !running(grandchild.id) }
    }

    @Test fun subagentsListsChildrenAndCancelsOne() = test {
        val parent = session("default")
        call(parent, "delegate", "agent" to "sleeper", "goal" to "long job", "background" to true)
        val child = children("sleeper").single()
        until { running(child.id) }
        val (_, list) = call(parent, "subagents")
        assertEquals("- ${child.id} sleeper \"long job\" [running]", list)
        assertEquals("Stopped ${child.id}.", call(parent, "subagents", "action" to "cancel", "session_id" to child.id).second)
        until { !running(child.id) }
        assertTrue(call(session("default"), "subagents", "action" to "cancel", "session_id" to child.id).first)
    }

    @Test fun aHiddenChildNeedsAnOnDeviceModel() = test {
        val parent = session("default")
        val (error, text) = call(parent, "delegate", "agent" to "worker", "goal" to "x", "hidden" to true)
        assertTrue(error)
        assertTrue("on-device" in text && "cloud/echo" in text)
        assertTrue(children("worker").isEmpty())
        assertFalse(call(parent, "delegate", "agent" to "private", "goal" to "x", "hidden" to true, "model" to "echo").first)
        assertTrue(call(parent, "delegate", "agent" to "worker", "goal" to "x", "hidden" to true, "model" to "small").first)
    }

    @Test fun aHiddenChildsTranscriptIsInvisibleToTheParentAndOnlyItsResultCrosses() = test {
        val parent = session("default")
        val (error, text) = call(parent, "delegate", "agent" to "private", "goal" to "read my medical notes")
        assertTrue(!error && text.startsWith("small: read my medical notes"), text)
        // Not hidden unless asked.
        assertFalse(children("private").single().hidden)

        val (_, hiddenText) = call(parent, "delegate", "agent" to "private", "goal" to "diagnosis details", "hidden" to true)
        assertTrue(hiddenText.startsWith("small: diagnosis details"))
        val hidden = children("private").single { it.hidden }
        assertTrue(call(parent, "sessions_read", "session_id" to hidden.id).first)
        assertTrue(call(parent, "session_status", "session_id" to hidden.id).first)
        assertFalse(hidden.id in call(parent, "sessions_list").second)
        assertFalse("diagnosis" in call(parent, "sessions_search", "query" to "diagnosis").second)
        assertTrue("- ${hidden.id} private \"diagnosis details\" [finished, hidden]" in call(parent, "subagents").second)
        assertTrue(call(CallerScope("private", hidden.id), "sessions_read", "session_id" to hidden.id).first)
    }

    @Test fun aHiddenChildCannotWriteTheAgentsMemory() = test {
        val parent = session("default")
        call(parent, "delegate", "agent" to "private", "goal" to "ordinary")
        val ordinary = advertised.getValue("local/small")
        call(parent, "delegate", "agent" to "private", "goal" to "private", "hidden" to true)
        val hidden = advertised.getValue("local/small")
        assertTrue("memory" in ordinary)
        assertTrue(hidden.none { it in setOf("memory", "knowledge", "skill_manage") })
    }

    @Test fun workHandedOnByAHiddenSessionStaysHidden() = test {
        val parent = session("default")
        call(parent, "delegate", "agent" to "private", "goal" to "hidden job", "hidden" to true)
        val hidden = children("private").single()
        val scope = CallerScope("private", hidden.id).trusted()
        val (error, text) = call(scope, "delegate", "agent" to "worker", "goal" to "send it to the cloud")
        assertTrue(error)
        assertTrue("on-device" in text)
        val (failed, local) = call(scope, "delegate", "agent" to "private", "goal" to "more private work")
        assertFalse(failed)
        assertTrue(children("private").any { it.hidden && it.parent?.sessionId == hidden.id })
        assertTrue(local.startsWith("small: more private work"))
    }

    @Test fun hiddenSessionsAreNotReflectedOn() = test {
        harness.saveKnowledgeSettings(KnowledgeSettings(conventions = mapOf("markdown" to "People go in person notes")))
        val agent = harness.store.agents().first { it.id == "default" }
        val reflector = Reflector(harness, CoroutineScope(Dispatchers.Default), busy = { false })
        val visible = harness.newSession(agent).id
        harness.store.append("default", visible, SessionEntry.UserMessage("My sister Ana lives in Dublin."))
        reflector.reflect(agent, visible)
        assertEquals(1, harness.store.entries("default", visible).count { it is SessionEntry.Reflection })

        val hidden = harness.newSession(agent).id
        harness.store.append("default", hidden, SessionEntry.Details(hidden = true))
        harness.store.append("default", hidden, SessionEntry.UserMessage("My sister Ana lives in Dublin."))
        assertEquals(0, reflector.reflect(agent, hidden))
        assertEquals(0, harness.store.entries("default", hidden).count { it is SessionEntry.Reflection })
    }

    @Test fun reviewShowsTheUserTheHiddenResultBeforeItReachesTheParent() = test {
        val parent = session("default")
        // The parent is mid-run, as it is when a tool call reviews.
        harness.log.publish("default", parent.sessionId!!, AgentEvent.RunStarted("run"))
        for ((allow, goal) in listOf(true to "first", false to "second")) {
            val call = async { call(parent, "delegate", "agent" to "private", "goal" to goal, "hidden" to true, "review" to true) }
            until { harness.log.live(parent.sessionId!!).approval != null }
            val approval = harness.log.live(parent.sessionId!!).approval!!
            assertTrue("small: $goal" in approval.args)
            assertFalse(call.isCompleted)
            harness.approvals.respond(approval.approvalId, if (allow) ApprovalDecision.AllowOnce else ApprovalDecision.Deny())
            val (error, text) = call.await()
            assertFalse(error, text)
            if (allow) assertTrue(text.startsWith("small: $goal")) else assertTrue(text.startsWith("The user withheld the result.") && "small:" !in text)
        }
        assertNull(harness.log.live(parent.sessionId!!).approval)
    }
}
