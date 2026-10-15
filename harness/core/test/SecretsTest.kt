package kinuvo.harness.core

import kinuvo.protocol.SecretEntry
import kinuvo.protocol.SecretProviderStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class SecretsTest {
    private class FakeProvider : SecretProvider {
        override val scheme = "pass"
        override val name = "Fake"
        val reads = mutableListOf<Pair<String, String>>()
        var values = mapOf("pass://Kinuvo/GitHub/token" to "ghp_1")
        override suspend fun resolve(uri: String, reason: String): String {
            reads += uri to reason
            return values[uri] ?: error("Field does not exist")
        }
        override suspend fun status() = SecretProviderStatus(scheme, name)
        override suspend fun connect(token: String) {}
        override suspend fun disconnect() {}
        override suspend fun browse(path: List<String>) = emptyList<SecretEntry>()
    }

    private val provider = FakeProvider()
    private val time = TestTimeSource()
    private val secrets = Secrets(listOf(provider), ttl = 5.minutes, time = time)

    @Test
    fun expandsVariablesAndReferencesInOnePass() = runTest {
        val value = secrets.expand("Bearer \${pass://Kinuvo/GitHub/token} for \${USER} \${HOME:-/home}", mapOf("USER" to "\${OTHER}"), "Kinuvo: test")
        assertEquals("Bearer ghp_1 for \${OTHER} /home", value)
        assertEquals(listOf("pass://Kinuvo/GitHub/token" to "Kinuvo: test"), provider.reads)
    }

    @Test
    fun variablesCanHoldReferences() = runTest {
        val env = mapOf("GITHUB_TOKEN" to "\${pass://Kinuvo/GitHub/token}")
        assertEquals("token ghp_1", secrets.expand("token \${GITHUB_TOKEN}", env, "Kinuvo: test"))
    }

    @Test
    fun cachesUntilTtlOrRefresh() = runTest {
        repeat(2) { secrets.resolve("pass://Kinuvo/GitHub/token", "r") }
        assertEquals(1, provider.reads.size)
        time += 6.minutes
        provider.values = mapOf("pass://Kinuvo/GitHub/token" to "ghp_2")
        assertEquals("ghp_2", secrets.resolve("pass://Kinuvo/GitHub/token", "r"))
        provider.values = mapOf("pass://Kinuvo/GitHub/token" to "ghp_3")
        secrets.refresh()
        assertEquals("ghp_3", secrets.resolve("pass://Kinuvo/GitHub/token", "r"))
    }

    @Test
    fun refreshWinsOverAReadInFlight() = runTest {
        lateinit var secrets: Secrets
        var reads = 0
        val racing = object : SecretProvider by provider {
            override suspend fun resolve(uri: String, reason: String) = "stale".also { reads++; secrets.refresh() }
        }
        secrets = Secrets(listOf(racing), time = time)
        repeat(2) { secrets.resolve("pass://Kinuvo/GitHub/token", "r") }
        assertEquals(2, reads)
    }

    @Test
    fun redactsSecretsInMemory() = runTest {
        secrets.resolve("pass://Kinuvo/GitHub/token", "r")
        assertEquals("token=•••• ok", secrets.redact("token=ghp_1 ok"))
        val echo = object : kinuvo.harness.core.tools.Tool {
            override val descriptor = ai.koog.agents.core.tools.ToolDescriptor("echo", "")
            override suspend fun execute(args: kotlinx.serialization.json.JsonObject): String = error("bad ghp_1")
        }
        assertEquals("bad ••••", assertFailsWith<IllegalStateException> { secrets.redacting(echo).execute(kotlinx.serialization.json.JsonObject(emptyMap())) }.message)
        time += 6.minutes
        assertEquals("token=ghp_1", secrets.redact("token=ghp_1"))
    }

    @Test
    fun errorsNameTheReference() = runTest {
        val missing = assertFailsWith<IllegalStateException> { secrets.expand("\${pass://Kinuvo/None/token}", emptyMap(), "r") }
        assertEquals("Can't read pass://Kinuvo/None/token: Field does not exist", missing.message)
        assertFailsWith<IllegalStateException> { secrets.expand("\${op://Vault/Item/field}", emptyMap(), "r") }
        assertFailsWith<IllegalStateException> { secrets.expand("\${UNSET}", emptyMap(), "r") }
    }

    @Test
    fun recognizesSingleReferences() {
        assertTrue(Secrets.isReference("\${pass://Kinuvo/Anytype/api key}"))
        assertFalse(Secrets.isReference("Bearer \${pass://Kinuvo/GitHub/token}"))
        assertFalse(Secrets.isReference("plain-key"))
        assertTrue(Secrets.hasSecret("--token=\${pass://Kinuvo/GitHub/token}"))
        assertFalse(Secrets.hasSecret("--token=\${TOKEN}"))
        val reference = kinuvo.protocol.secretReference("pass", listOf("My Vault", "GitHub/API", "Prod.key}"))
        assertEquals("\${pass://My Vault/GitHub%2FAPI/Prod.key%7D}", reference)
        assertTrue(Secrets.isReference(reference))
    }
}
