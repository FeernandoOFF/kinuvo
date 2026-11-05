package kinuvo.harness.bitwarden

import kinuvo.harness.core.Keychain
import kinuvo.protocol.SecretEntry
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitwardenTest {
    private val dir = Files.createTempDirectory("bitwarden").toFile()
    private val keychain = FakeKeychain()

    /** A stand-in for bws: token `good` signs in; the server URL is echoed into the log. */
    private val cli = File(dir, "bws").apply {
        writeText(
            """
            #!/bin/bash
            echo "${'$'}BWS_SERVER_URL ${'$'}@" >> "${File(dir, "args").path}"
            [ "${'$'}1" = --version ] && { echo "bws 1.0.0"; exit 0; }
            [ "${'$'}BWS_ACCESS_TOKEN" = good ] || { echo "Error: Access token is not valid" >&2; exit 1; }
            case "${'$'}1 ${'$'}2" in
              "project list") echo '[{"id":"p1","name":"Kinuvo"},{"id":"p2","name":"Other"}]' ;;
              "secret list")
                [ "${'$'}3" = p1 ] && echo '[{"id":"s1","projectId":"p1","key":"GITHUB_TOKEN","value":"ghp_1"},{"id":"s2","projectId":"p1","key":"ANYTYPE","value":"any"}]' || echo '[]' ;;
              "secret get") [ "${'$'}3" = s1 ] && echo '{"id":"s1","key":"GITHUB_TOKEN","value":"ghp_1"}' || { echo "Error: not found" >&2; exit 1; } ;;
              *) echo "Error: unknown" >&2; exit 2 ;;
            esac
            """.trimIndent() + "\n"
        )
        setExecutable(true)
    }

    private fun bws(env: Map<String, String> = emptyMap(), keychain: Keychain? = this.keychain, cli: String = this.cli.path) =
        Bitwarden(dir, { env }, keychain, cli)

    @Test
    fun readsByProjectAndKeyOrById() = runTest {
        keychain.token = "good"
        val bws = bws(mapOf("BWS_SERVER_URL" to "https://vault.bitwarden.eu"))
        assertEquals("ghp_1", bws.resolve("bws://Kinuvo/GITHUB_TOKEN", "r"))
        assertEquals("ghp_1", bws.resolve("bws://s1", "r"))
        assertEquals("No secret NOPE in Kinuvo", assertFailsWith<IllegalStateException> { bws.resolve("bws://Kinuvo/NOPE", "r") }.message)
        assertEquals("No project Missing shared with Kinuvo", assertFailsWith<IllegalStateException> { bws.resolve("bws://Missing/X", "r") }.message)
        val args = File(dir, "args").readText()
        assertFalse("good" in args)
        assertTrue(args.lines().filter { it.isNotBlank() }.all { it.startsWith("https://vault.bitwarden.eu ") })
    }

    @Test
    fun connectKeepsOnlyATokenThatSignsIn() = runTest {
        val bws = bws()
        assertEquals("Access token is not valid", assertFailsWith<IllegalStateException> { bws.connect("bad") }.message)
        assertNull(keychain.token)
        bws.connect(" good ")
        assertEquals("good", keychain.token)
        assertTrue(bws.status().let { it.connected && "2 projects" in it.detail && it.levels == 2 })
        bws.disconnect()
        assertFalse(bws.status().connected)
    }

    @Test
    fun browsesKeysNotValues() = runTest {
        keychain.token = "good"
        val bws = bws()
        assertEquals(listOf(SecretEntry("Kinuvo"), SecretEntry("Other")), bws.browse(emptyList()))
        val keys = bws.browse(listOf("Kinuvo"))
        assertEquals(listOf("ANYTYPE", "GITHUB_TOKEN"), keys.map { it.name })
        assertFalse(keys.toString().contains("ghp_1"))
    }

    @Test
    fun fallsBackToTheEnvironmentWithoutAKeychain() = runTest {
        val bws = bws(env = mapOf("BWS_ACCESS_TOKEN" to "good"), keychain = null)
        assertEquals("ghp_1", bws.resolve("bws://Kinuvo/GITHUB_TOKEN", "r"))
        assertTrue(File(dir, ".env").path in assertFailsWith<IllegalStateException> { bws.connect("good") }.message!!)
    }

    @Test
    fun reportsAMissingCli() = runTest {
        assertFalse(bws(cli = File(dir, "absent").path).status().installed)
    }

    private class FakeKeychain : Keychain {
        var token: String? = null
        override val name = "Fake Keychain"
        override suspend fun read() = token
        override suspend fun write(token: String) { this.token = token }
        override suspend fun delete() { token = null }
    }
}
