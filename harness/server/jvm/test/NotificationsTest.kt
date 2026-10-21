package kinuvo.harness.server

import kinuvo.harness.testing.*

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.client.KinuvoClient
import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.core.PrivateFile
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.McpManagement
import kinuvo.harness.core.store.saveServiceConfig
import kinuvo.harness.core.Notification
import kinuvo.harness.core.NotificationProvider
import kinuvo.harness.core.store.serviceConfig
import kinuvo.harness.core.Notifier
import kinuvo.protocol.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationsTest {
    private val home = Files.createTempDirectory("harness-notify").toFile()
    private val sent = Channel<Notification>(Channel.UNLIMITED)
    private val provider = object : NotificationProvider {
        override val id = "test"
        override val settings = SettingsSchema(listOf(SettingsField("token", "Token", sensitive = true), SettingsField("topic", "Topic")))
        override fun configured(options: Map<String, String>, host: kinuvo.harness.core.PluginHost) = Notifier { sent.send(it) }
    }

    @Test
    fun notifiesWhenARoutineFindsAnMcpServerNeedingSignIn() = runBlocking {
        var signInNeeded: (suspend (String, String) -> Unit)? = null
        val plugin = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.notificationstest.1", "Test")
            override val notifications = listOf(provider)
            override val mcp = object : McpManagement by McpManagement.Unavailable {
                override fun onSignInNeeded(listener: suspend (agentId: String, server: String) -> Unit) { signInNeeded = listener }
            }
        }
        val harness = Harness(Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))), plugins = listOf(plugin))
        harness.files.saveServiceConfig(ServiceKind.Notifications, ServiceSettings("test", emptyMap()))
        val server = HarnessServer(harness)
        server.start("127.0.0.1", 0, schedule = false)
        try {
            signInNeeded!!("default", "linear")
            assertEquals("MCP server 'linear' needs sign-in", withTimeout(5_000) { sent.receive() }.title)
        } finally {
            server.stop()
        }
    }

    @Test
    fun notifiesWhenNoClientInTheForegroundWatches() = runBlocking {
        val model = scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("All done"), StreamFrame.End()) }
        val plugin = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.notificationstest.2", "Test")
            override val notifications = listOf(provider) }
        val harness = Harness(Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))), modelFactory = { model }, plugins = listOf(plugin))
        val server = HarnessServer(harness)
        val port = server.start("127.0.0.1", 0, schedule = false)
        val phone = KinuvoClient("ws://127.0.0.1:$port/ws", "phone", server.auth.sessionToken(), timeZone = "UTC")
        try {
            withTimeout(10_000) { phone.connect() }
            assertEquals("Pick a provider first", phone.call(Api.TestService, TestServiceParams(ServiceKind.Notifications)).error)
            phone.call(Api.SaveService, SaveServiceParams(ServiceKind.Notifications, ServiceSettings("test", mapOf("topic" to "t")), mapOf("token" to SecretUpdate.Replace("tk"))))
            assertEquals(mapOf("token" to KnowledgeSettings.SECRET, "topic" to "t"), phone.call(Api.Service, ServiceRef(ServiceKind.Notifications)).settings.options)
            phone.call(Api.SaveService, SaveServiceParams(ServiceKind.Notifications, ServiceSettings("test", mapOf("topic" to "t2")), mapOf("token" to SecretUpdate.Keep)))
            assertEquals(ServiceSettings("test", mapOf("token" to "tk", "topic" to "t2")), harness.files.serviceConfig(ServiceKind.Notifications))
            val replacement = phone.call(Api.SaveService, SaveServiceParams(ServiceKind.Notifications, ServiceSettings("test", mapOf("topic" to "t2")),
                mapOf("token" to SecretUpdate.Replace("tk2"))))
            assertEquals(setOf("token"), replacement.configuredSecrets)
            assertEquals("Token", replacement.providers.single().schema.fields.first().label)
            assertEquals(KnowledgeSettings.SECRET, replacement.settings.options["token"])
            phone.call(Api.SaveService, SaveServiceParams(ServiceKind.Notifications, ServiceSettings("test", mapOf("topic" to "t2")),
                mapOf("token" to SecretUpdate.Keep)))
            assertEquals("tk2", harness.files.serviceConfig(ServiceKind.Notifications).options["token"])
            phone.call(Api.SaveService, SaveServiceParams(ServiceKind.Notifications, ServiceSettings("test", mapOf("topic" to "t2")),
                mapOf("token" to SecretUpdate.Remove)))
            assertNull(harness.files.serviceConfig(ServiceKind.Notifications).options["token"])
            val sessionId = phone.call(Api.CreateSession, AgentRef("default")).id
            phone.call(Api.CatchUp, CatchUpParams("default", sessionId))
            suspend fun turn() = coroutineScope {
                val idle = async(start = CoroutineStart.UNDISPATCHED) { phone.events(Api.SessionChanged).filter { it.id == sessionId && it.status == SessionStatus.Idle }.first() }
                phone.call(Api.Send, SendParams("default", sessionId, "hi"))
                withTimeout(10_000) { idle.await() }
            }
            turn()
            assertNull(withTimeoutOrNull(500) { sent.receive() })

            phone.call(Api.Presence, PresenceParams(active = false))
            turn()
            val n = withTimeout(5_000) { sent.receive() }
            assertEquals(listOf("Kinuvo", "All done", sessionId), listOf(n.title, n.body, n.sessionId))

            assertEquals("Sent", phone.call(Api.TestService, TestServiceParams(ServiceKind.Notifications, input = "ping")).summary)
            assertEquals("ping", withTimeout(5_000) { sent.receive() }.body)
        } finally {
            phone.close()
            server.stop()
        }
    }

    @Test
    fun aRoutineRunInTheUsersSessionIsNotToldAsAReply(): Unit = runBlocking {
        val plugin = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.notificationstest.3", "Test")
            override val notifications = listOf(provider) }
        val harness = Harness(Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))), plugins = listOf(plugin))
        harness.saveService(ServiceKind.Notifications, ServiceSettings("test"))
        val agent = harness.store.agents().single()
        val session = harness.newSession(agent).id
        harness.store.append(agent, session, SessionEntry.AssistantMessage("[SILENT]"))
        var routine: RoutineRef? = RoutineRef("hb", "Heartbeat")
        val notifications = Notifications(harness, this) { routine }
        notifications.on(agent, session, AgentEvent.RunFinished("r1"))
        assertNull(withTimeoutOrNull(500) { sent.receive() })
        notifications.on(agent, session, AgentEvent.QuestionsAsked("q1", "f1", "Which flight?", emptyList()))
        assertEquals(listOf("Kinuvo has questions", "Which flight?"), withTimeout(5_000) { sent.receive() }.let { listOf(it.title, it.body) })
        routine = null
        notifications.on(agent, session, AgentEvent.RunFinished("r2"))
        assertEquals("[SILENT]", withTimeout(5_000) { sent.receive() }.body)
    }
}
