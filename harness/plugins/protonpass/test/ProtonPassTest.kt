package kinuvo.harness.protonpass

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

class ProtonPassTest {
    private val dir = Files.createTempDirectory("protonpass").toFile()
    private val session = File(dir, "session")
    private val reasons = File(dir, "reasons")
    private val keychain = FakeKeychain()

    /** A stand-in for pass-cli: token `good` signs in; items need a session and a reason. */
    private val cli = File(dir, "pass-cli").apply {
        writeText(
            """
            #!/bin/bash
            S="${'$'}PROTON_PASS_SESSION_DIR/session"
            need() { [ -f "${'$'}S" ] || { echo "Error: Unauthorized" >&2; exit 1; }; }
            case "${'$'}1 ${'$'}2" in
              "--version "*) echo "Proton Pass CLI 2.3.3" ;;
              "login "*) [ "${'$'}PROTON_PASS_PERSONAL_ACCESS_TOKEN" = good ] || { echo "Error: Invalid token" >&2; exit 1; }; mkdir -p "${'$'}PROTON_PASS_SESSION_DIR"; touch "${'$'}S" ;;
              "logout "*) rm -f "${'$'}S" ;;
              "info "*) need; echo "- Agent: kinuvo" ;;
              "vault list") need; echo '{"vaults":[{"name":"Kinuvo","vault_id":"v","share_id":"s"}]}' ;;
              "item list") need; echo '{"items":[{"id":"i","title":"GitHub","item_type":"login"},{"id":"j","title":"anytype","item_type":"custom"}]}' ;;
              "item view")
                need
                [ -n "${'$'}PROTON_PASS_AGENT_REASON" ] || { echo "Error: reason required" >&2; exit 1; }
                echo "${'$'}PROTON_PASS_AGENT_REASON" >> "${'$'}PROTON_PASS_SESSION_DIR/../reasons"
                case "${'$'}3" in
                  pass://Kinuvo/GitHub/password) echo "hunter2" ;;
                  --vault-name) [ "${'$'}6" = GitHub ] && echo '{"item":{"content":{"title":"GitHub","note":"","content":{"Login":{"email":"me@x.com","username":"","password":"hunter2","urls":["https://github.com"],"totp_uri":"otpauth://x","passkeys":[]}},"extra_fields":[{"name":"api_key","type":"hidden"}]}}}' \
                    || echo '{"item":{"content":{"title":"anytype","note":"n","content":{"Custom":{"sections":[{"section_name":"","section_fields":[{"name":"key","content":{"Hidden":"s1"}}]},{"section_name":"Prod","section_fields":[{"name":"key","content":{"Hidden":"s2"}}]}]}},"extra_fields":[]}}}' ;;
                  *) echo "Error: Field does not exist: x" >&2; exit 1 ;;
                esac ;;
              *) echo "Error: unknown" >&2; exit 2 ;;
            esac
            """.trimIndent() + "\n"
        )
        setExecutable(true)
    }

    private fun pass(env: Map<String, String> = emptyMap(), keychain: Keychain? = this.keychain, cli: String = this.cli.path) =
        ProtonPass(File(dir, "pass-session"), { env }, keychain, cli)

    @Test
    fun signsInWhenNeededAndSendsTheReason() = runTest {
        keychain.token = "good"
        val pass = pass()
        assertEquals("hunter2", pass.resolve("pass://Kinuvo/GitHub/password", "Kinuvo: MCP server github (Agent default)"))
        File(dir, "pass-session/session").delete()
        assertEquals("hunter2", pass.resolve("pass://Kinuvo/GitHub/password", "again"))
        assertEquals(listOf("Kinuvo: MCP server github (Agent default)", "again"), reasons.readLines())
        val missing = assertFailsWith<IllegalStateException> { pass.resolve("pass://Kinuvo/GitHub/nope", "r") }
        assertEquals("Field does not exist: x", missing.message)
    }

    @Test
    fun connectKeepsOnlyATokenThatSignsIn() = runTest {
        val pass = pass()
        assertEquals("Invalid token", assertFailsWith<IllegalStateException> { pass.connect("bad") }.message)
        assertNull(keychain.token)
        pass.connect(" good ")
        assertEquals("good", keychain.token)
        keychain.token = null
        pass.connect("# How to use pass-cli\n```\n$TOKEN_VAR=\"good\" pass-cli login\n```")
        assertEquals("good", keychain.token)
        assertTrue(pass.status().let { it.connected && "1 vaults" in it.detail })
        pass.disconnect()
        assertNull(keychain.token)
        assertFalse(pass.status().connected)
    }

    @Test
    fun fallsBackToTheEnvironmentWithoutAKeychain() = runTest {
        val pass = pass(env = mapOf("PROTON_PASS_PERSONAL_ACCESS_TOKEN" to "good"), keychain = null)
        assertEquals("hunter2", pass.resolve("pass://Kinuvo/GitHub/password", "r"))
        assertTrue("ROTON_PASS_PERSONAL_ACCESS_TOKEN" in pass.status().tokenStorage)
        assertTrue(File(dir, ".env").path in pass.status().tokenStorage)
        assertTrue(File(dir, ".env").path in assertFailsWith<IllegalStateException> { pass.connect("good") }.message!!)
        assertTrue(File(dir, ".env").path in assertFailsWith<IllegalStateException> { pass.disconnect() }.message!!)
    }

    @Test
    fun browsesNamesNotValues() = runTest {
        keychain.token = "good"
        val pass = pass()
        assertEquals(listOf(SecretEntry("Kinuvo")), pass.browse(emptyList()))
        assertEquals(listOf(SecretEntry("anytype", "custom"), SecretEntry("GitHub", "login")), pass.browse(listOf("Kinuvo")))
        val fields = pass.browse(listOf("Kinuvo", "GitHub"))
        assertEquals(listOf("email", "password", "url", "totp", "api_key"), fields.map { it.name })
        assertFalse(fields.toString().contains("hunter2"))
        assertEquals(listOf("key", "Prod.key", "note"), pass.browse(listOf("Kinuvo", "anytype")).map { it.name })
    }

    @Test
    fun reportsAMissingCli() = runTest {
        val status = pass(cli = File(dir, "absent").path).status()
        assertFalse(status.installed)
        assertTrue("brew install protonpass/tap/pass-cli" in status.detail)
    }

    private companion object {
        const val TOKEN_VAR = "PROTON_PASS_PERSONAL_ACCESS_TOKEN"
    }

    private class FakeKeychain : Keychain {
        var token: String? = null
        override val name = "Fake Keychain"
        override suspend fun read() = token
        override suspend fun write(token: String) { this.token = token }
        override suspend fun delete() { token = null }
    }
}
