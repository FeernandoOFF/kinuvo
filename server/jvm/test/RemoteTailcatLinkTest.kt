package kinuvo.server.jvm

import kinuvo.client.KinuvoClient
import kinuvo.client.TailcatForward
import kinuvo.client.TailcatForwarder
import kinuvo.client.TailcatPairing
import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.execution.*
import kinuvo.server.plugin.TailcatInvite
import kinuvo.server.plugin.TailcatServing
import kinuvo.server.plugin.TailcatTunnel
import kinuvo.testing.harness.TemporaryDirectories
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.*

class RemoteTailcatLinkTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest
    fun deleteTemporaryDirectories() = temporaryDirectories.close()

    private class Tunnel(val port: Int) : TailcatTunnel {
        val payloads = mutableMapOf<String, String>()
        val revoked = mutableListOf<String>()
        override fun invite(code: String, payload: String, ttlSeconds: Int, boundPhones: Set<String>): TailcatInvite {
            val text = "kinuvo1:$code"
            payloads[text] = payload
            return object : TailcatInvite {
                override val text = text
                override val expiresAt = 4_000_000_000L
                override val open = true
                override fun close() = Unit
            }
        }
        override fun phoneOf(code: String) = PHONE.takeIf { "kinuvo1:$code" in payloads }
        override fun peerAt(port: Int) = PHONE
        override fun revoke(phone: String) { revoked += phone }
        override fun close() = Unit
    }

    private class Serving : TailcatServing {
        var tunnel: Tunnel? = null
        val offline = mutableListOf<String>()
        override fun serve(state: Path, port: Int, name: String) = Tunnel(port).also { tunnel = it }
        override fun reset(state: Path) = Unit
        override fun revoke(state: Path, phone: String) { offline += phone }
    }

    private class Forwarder(private val serving: Serving) : TailcatForwarder {
        override val supported = true
        var payload: String? = null
        var opened = 0
        override suspend fun pair(invite: String, device: String): TailcatPairing {
            val tunnel = requireNotNull(serving.tunnel)
            return TailcatPairing("secret-link", payload ?: tunnel.payloads.getValue(invite), "Source")
        }
        override suspend fun open(link: String): TailcatForward {
            assertEquals("secret-link", link)
            opened++
            val port = requireNotNull(serving.tunnel).port
            return object : TailcatForward {
                override val port = port
                override suspend fun close() = Unit
            }
        }
    }

    @Test
    fun aServerLinksAnotherThroughOneInviteAndRevocationUnpairsItsPhone() = runBlocking {
        withTimeout(60_000) {
            val dir = temporaryDirectories.create("kinuvo-link-tailcat").toFile()
            val serving = Serving()
            val forwarder = Forwarder(serving)
            val source = JvmServerHost(JvmAgentRuntime(home = Path(dir.resolve("source").path)), tailcat = serving)
            val local = JvmServerHost(JvmAgentRuntime(home = Path(dir.resolve("local").path)), forwarder = forwarder)
            val clients = mutableListOf<KinuvoClient>()
            suspend fun manager(server: JvmServerHost, port: Int) = KinuvoClient("ws://127.0.0.1:$port/ws", "manager", pairingCode = server.auth.pairing(false, true)!!.code).also { clients.add(it); it.connect() }
            try {
                val sourcePort = source.start("127.0.0.1", 0, schedule = false)
                val origin = manager(source, sourcePort)
                val destination = manager(local, local.start("127.0.0.1", 0, schedule = false))
                val grant = ExposureGrant("studio", setOf(AgentId("default")))
                origin.call(Api.GrantExecution, grant)
                assertFalse(ServerFeature.LinkInvite in source.runtime.capabilities.features)
                assertFails { origin.call(Api.InviteExecution, ExecutionRef("studio")) }
                assertTrue(ServerFeature.LinkJoin in local.runtime.capabilities.features)
                assertFalse(ServerFeature.LinkJoin in source.runtime.capabilities.features)

                source.shareTailcat(true)
                assertTrue(ServerFeature.LinkInvite in source.runtime.capabilities.features)
                assertFails { origin.call(Api.InviteExecution, ExecutionRef("missing")) }
                val invite = origin.call(Api.InviteExecution, ExecutionRef("studio"))
                assertTrue(invite.link.startsWith("kinuvo://link?tailcat="))
                assertNull(source.runtime.remoteLinks.grant("studio")!!.phone)

                assertFails { destination.call(Api.SaveRemoteLink, SaveRemoteLink("x", "tailcat:abc", "token")) }
                forwarder.payload = """{"code":"123456"}"""
                val wrong = assertFails { destination.call(Api.JoinRemote, JoinRemoteLink("studio-source", invite.invite)) }
                assertContains(wrong.message.orEmpty(), "pairs a Device")
                assertFails { destination.call(Api.JoinRemote, JoinRemoteLink("studio-source", "https://example.com")) }
                forwarder.payload = null

                destination.call(Api.JoinRemote, JoinRemoteLink("studio-source", invite.link))
                assertEquals(listOf("default"), destination.call(Api.DiscoverRemote, ExecutionRef("studio-source")).agents.map { it.id.value })
                assertEquals(PHONE, source.runtime.remoteLinks.grant("studio")!!.phone)
                assertTrue(forwarder.opened > 0)
                val error = assertFails { origin.call(Api.InviteExecution, ExecutionRef("studio")) }
                assertContains(error.message.orEmpty(), "already linked")

                origin.call(Api.GrantExecution, grant)
                assertEquals(listOf(PHONE), serving.tunnel!!.revoked)
                assertNull(source.runtime.remoteLinks.grant("studio")!!.phone)

                val again = origin.call(Api.InviteExecution, ExecutionRef("studio"))
                destination.call(Api.JoinRemote, JoinRemoteLink("studio-source", again.invite))
                destination.call(Api.DiscoverRemote, ExecutionRef("studio-source"))
                assertEquals(PHONE, source.runtime.remoteLinks.grant("studio")!!.phone)
                origin.call(Api.RevokeExecution, ExecutionRef("studio"))
                assertEquals(listOf(PHONE, PHONE), serving.tunnel!!.revoked)
                assertEquals(emptyList(), origin.call(Api.ExecutionGrants, Unit))

                origin.call(Api.GrantExecution, grant)
                val last = origin.call(Api.InviteExecution, ExecutionRef("studio"))
                destination.call(Api.JoinRemote, JoinRemoteLink("studio-source", last.invite))
                destination.call(Api.DiscoverRemote, ExecutionRef("studio-source"))
                source.shareTailcat(false)
                assertFalse(ServerFeature.LinkInvite in source.runtime.capabilities.features)
                origin.call(Api.RevokeExecution, ExecutionRef("studio"))
                assertEquals(listOf(PHONE), serving.offline)
            } finally {
                clients.forEach { it.close() }
                local.stop()
                source.stop()
            }
        }
    }

    private companion object { const val PHONE = "nodekey:linked" }
}
