package kinuvo.harness.codex

import kinuvo.harness.core.*
import kinuvo.harness.core.store.*
import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.encodeToString
import kotlin.test.*

class OpenAIKeyTest {
    private val home = Files.createTempDirectory("openai-key").toFile()
    private val files = AgentFiles(Path(home.path))
    private val keyFile = Path(files.home, "credentials", "openai-key")
    private val refFile = Path(files.home, "credentials", "openai-reference")
    private fun keys(secrets: Secrets = Secrets(), env: Map<String, String> = emptyMap()) =
        OpenAIKey(files, secrets, PrivateFile(keyFile), PrivateFile(refFile), { env })

    @Test
    fun savesOwnerOnlyKeyWithoutReturningItAndFallsBackToEnvironment() = runTest {
        try {
            val keys = keys(env = mapOf("OPENAI_API_KEY" to "environment-key"))
            keys.save(" pasted-key ")
            assertEquals("pasted-key", keys.resolve())
            val status = keys.status()
            assertTrue(status.configured)
            assertTrue(status.saved)
            assertNull(status.reference)
            assertEquals(keyFile.toString(), status.storage)
            assertFalse(ProtocolJson.encodeToString(status).contains("pasted-key"))
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(java.nio.file.Path.of(keyFile.toString())))
            assertEquals("pasted-key", keys().resolve())
            keys.save("")
            assertFalse(keyFile.exists())
            assertEquals("environment-key", keys.resolve())
            assertFalse(keys.status().saved)
            assertFalse(keys().status().configured)
        } finally { home.deleteRecursively() }
    }

    @Test
    fun storesOnlyReferenceAndResolvesThroughSecrets() = runTest {
        var secretValue = "first-value"
        var reads = 0
        val provider = object : SecretProvider {
            override val scheme = "pass"
            override val name = "Test vault"
            override suspend fun resolve(uri: String, reason: String): String {
                assertEquals("pass://Vault/OpenAI/api key", uri)
                reads++
                return secretValue
            }
            override suspend fun status() = SecretProviderStatus(scheme, name)
            override suspend fun connect(token: String) {}
            override suspend fun disconnect() {}
            override suspend fun browse(path: List<String>) = emptyList<SecretEntry>()
        }
        val secrets = Secrets(listOf(provider))
        try {
            val keys = keys(secrets)
            keys.save("old-key")
            val reference = "\${pass://Vault/OpenAI/api key}"
            keys.save(reference)
            assertFalse(keyFile.exists())
            assertEquals(reference, refFile.readText())
            assertEquals(reference, keys.status().reference)
            assertEquals("first-value", keys.resolve())
            assertEquals(1, reads)
            secretValue = "rotated-value"
            secrets.refresh()
            assertEquals("rotated-value", keys.resolve())
            assertFalse(home.walkTopDown().filter { it.isFile }.any { it.readText().contains("rotated-value") })
            keys.save("new-key")
            assertFalse(refFile.exists())
            assertEquals("new-key", keys.resolve())
            assertFailsWith<IllegalStateException> { keys.save("\${missing://vault/key}") }
            assertEquals("new-key", keys.resolve())
        } finally { home.deleteRecursively() }
    }
}
