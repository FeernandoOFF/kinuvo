package kinuvo.server.embedded

import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.providers.codex.CodexAuth
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.deleteTree
import kinuvo.server.store.AgentFiles
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** A phone's Server offers Device-held live calls through the shared Codex provider once ChatGPT is signed in. */
class EmbeddedLiveCallsTest {
    private val home = Path(SystemTemporaryDirectory, "kinuvo-live-calls-${Uuid.random()}")

    @AfterTest
    fun cleanup() { deleteTree(home) }

    private fun keychain(signedIn: Boolean) = object : Keychain {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":${(Clock.System.now() + 1.hours).epochSeconds}}""".toByteArray())
        var token: String? = """{"auth_mode":"chatgpt","tokens":{"id_token":"id","access_token":"h.$payload.s","refresh_token":"r","account_id":"acct"}}""".takeIf { signedIn }
        override val name = "test"
        override suspend fun read() = token
        override suspend fun write(token: String) { this.token = token }
        override suspend fun delete() { token = null }
    }

    private fun liveCalls(signedIn: Boolean): Boolean = runBlocking {
        withTimeout(10_000) {
            val files = AgentFiles(home)
            val providers = sharedProviders(files, embeddedKeys(files), CodexAuth(store = keychain(signedIn)), { ByteArray(0) })
            val server = EmbeddedServerHost.create(home, plugins = providers.plugins, modelSources = providers.sources)
            val app = KinuvoClient("local://kinuvo", "Pixel", token = server.token, connection = server::connect)
            try {
                app.connect()
                app.call(Api.VoiceSetup, Unit).liveCalls
            } finally { app.close(); server.close() }
        }
    }

    @Test
    fun signedInOwnersGetLiveCalls() = assertEquals(true, liveCalls(signedIn = true))

    @Test
    fun signedOutOwnersDoNot() = assertEquals(false, liveCalls(signedIn = false))
}
