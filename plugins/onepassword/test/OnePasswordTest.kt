package kinuvo.plugins.onepassword

import kinuvo.server.plugin.Keychain
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

class OnePasswordTest {
    private val dir = Files.createTempDirectory("onepassword").toFile()
    private val keychain = FakeKeychain()

    /** A stand-in for op: token `ops_good` signs in; arguments are logged to check the token never is one. */
    private val cli = File(dir, "op").apply {
        writeText(
            """
            #!/bin/bash
            echo "${'$'}@" >> "${'$'}OP_CONFIG_DIR/../args"
            [ "${'$'}1" = --version ] && { echo 2.30.0; exit 0; }
            [ "${'$'}OP_SERVICE_ACCOUNT_TOKEN" = ops_good ] || { echo "[ERROR] invalid service account token" >&2; exit 1; }
            case "${'$'}1 ${'$'}2" in
              "read --no-newline")
                case "${'$'}3" in
                  op://Kinuvo/GitHub/password) printf hunter2 ;;
                  "op://Kinuvo/My API/Prod/key") printf s2 ;;
                  *) echo "[ERROR] could not read secret" >&2; exit 1 ;;
                esac ;;
              "vault list") echo '[{"id":"v1","name":"Kinuvo"}]' ;;
              "item list") echo '[{"id":"i1","title":"GitHub","category":"LOGIN"},{"id":"i2","title":"My API","category":"API_CREDENTIAL"}]' ;;
              "item get") echo '{"id":"i2","fields":[{"id":"username","type":"STRING","label":"username","value":"me"},{"id":"k","type":"CONCEALED","label":"key","value":"s2","section":{"id":"s","label":"Prod"}}]}' ;;
              *) echo "[ERROR] unknown" >&2; exit 2 ;;
            esac
            """.trimIndent() + "\n"
        )
        setExecutable(true)
    }

    private fun op(env: Map<String, String> = emptyMap(), keychain: Keychain? = this.keychain, cli: String = this.cli.path) =
        OnePassword(dir, { env }, keychain, cli)

    @Test
    fun readsReferencesWithTheTokenInTheEnvironment() = runTest {
        keychain.token = "ops_good"
        val op = op()
        assertEquals("hunter2", op.resolve("op://Kinuvo/GitHub/password", "r"))
        assertEquals("s2", op.resolve("op://Kinuvo/My API/Prod%2Fkey", "r"))
        assertEquals("could not read secret", assertFailsWith<IllegalStateException> { op.resolve("op://Kinuvo/GitHub/nope", "r") }.message?.removePrefix("[ERROR] "))
        assertFalse(File(dir, "args").readText().contains("ops_good"))
    }

    @Test
    fun connectKeepsOnlyATokenThatSignsIn() = runTest {
        val op = op()
        assertFailsWith<IllegalArgumentException> { op.connect("not a token") }
        assertFailsWith<IllegalStateException> { op.connect("ops_bad") }
        assertNull(keychain.token)
        op.connect(" ops_good ")
        assertEquals("ops_good", keychain.token)
        assertTrue(op.status().let { it.connected && "1 vaults" in it.detail })
        op.disconnect()
        assertFalse(op.status().connected)
    }

    @Test
    fun fallsBackToTheEnvironmentWithoutAKeychain() = runTest {
        val op = op(env = mapOf("OP_SERVICE_ACCOUNT_TOKEN" to "ops_good"), keychain = null)
        assertEquals("hunter2", op.resolve("op://Kinuvo/GitHub/password", "r"))
        assertTrue(File(dir, ".env").path in op.status().tokenStorage)
        assertTrue(File(dir, ".env").path in assertFailsWith<IllegalStateException> { op.disconnect() }.message!!)
    }

    @Test
    fun browsesNamesNotValues() = runTest {
        keychain.token = "ops_good"
        val op = op()
        assertEquals(listOf(SecretEntry("Kinuvo")), op.browse(emptyList()))
        assertEquals(listOf(SecretEntry("GitHub", "login"), SecretEntry("My API", "api_credential")), op.browse(listOf("Kinuvo")))
        val fields = op.browse(listOf("Kinuvo", "My API"))
        assertEquals(listOf("username", "Prod/key"), fields.map { it.name })
        assertFalse(fields.toString().contains("s2"))
    }

    @Test
    fun reportsAMissingCli() = runTest {
        val status = op(cli = File(dir, "absent").path).status()
        assertFalse(status.installed)
        assertTrue("1password-cli" in status.detail)
    }

    private class FakeKeychain : Keychain {
        var token: String? = null
        override val name = "Fake Keychain"
        override suspend fun read() = token
        override suspend fun write(token: String) { this.token = token }
        override suspend fun delete() { token = null }
    }
}
