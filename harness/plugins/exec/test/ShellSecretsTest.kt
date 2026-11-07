package kinuvo.harness.exec

import kinuvo.harness.core.SecretScope
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.tools.Tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellSecretsTest {
    private val backend = HostBackend(listOf(Files.createTempDirectory("ws").toFile().canonicalFile))
    private val secrets = Secrets()
    private val scope = SecretScope("ada", "s1")
    private val value = "ghp_typed_value_42"

    private fun shell(scope: SecretScope? = this.scope): Tool = execTools(backend, secrets, scope, "ada").first { it.descriptor.name == "shell" }

    private suspend fun reference(owner: SecretScope = scope) = "\${${secrets.session.store(owner, value)}}"

    private fun call(command: String, vararg env: Pair<String, String>) = buildJsonObject {
        put("command", command)
        put("secrets", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
    }

    @Test
    fun secretsReachTheCommandAsEnvironmentVariablesNeverArguments() = runTest {
        val args = call("printf %s \"\$TOKEN\" | wc -c; ps -o args= -p \$\$", "TOKEN" to reference())
        val out = shell().execute(args)
        assertTrue(out.contains("${value.length}"), out)
        assertTrue("TOKEN" in out && value !in out, out)
    }

    @Test
    fun theOutputIsMasked() = runTest {
        val args = call("echo \$TOKEN; echo oops \$TOKEN >&2", "TOKEN" to reference())
        val out = secrets.redacting(shell()).execute(args)
        assertEquals("[exit code 0]\n••••\noops ••••", out)
    }

    @Test
    fun backgroundCommandsGetThemToo() = runTest {
        val tool = shell()
        val started = tool.execute(buildJsonObject {
            put("command", "printf %s \"\$TOKEN\" | wc -c"); put("background", true)
            put("secrets", buildJsonObject { put("TOKEN", reference()) })
        })
        assertTrue("Started background process bg1" in started, started)
    }

    @Test
    fun referencesInTheCommandAreRefused() = runTest {
        val ref = reference()
        val args = buildJsonObject { put("command", "curl -H 'Authorization: Bearer $ref' https://api.example") }
        val e = assertFailsWith<IllegalArgumentException> { shell().bind(args) }
        assertTrue("`secrets`" in e.message!!, e.message)
        assertFailsWith<IllegalArgumentException> { shell().execute(args) }
        assertFailsWith<IllegalArgumentException> { shell().execute(buildJsonObject { put("command", "echo \${pass://Kinuvo/GitHub/token}") }) }
        assertEquals("[exit code 0]\nhi", shell().execute(buildJsonObject { put("command", "x=hi; echo \${x}") }))
    }

    @Test
    fun onlyReferencesAndEnvNamesAreAccepted() = runTest {
        assertFailsWith<IllegalArgumentException> { shell().execute(call("true", "TOKEN" to value)) }
        assertFailsWith<IllegalArgumentException> { shell().execute(call("true", "TOKEN" to "\${HOME}")) }
        assertFailsWith<IllegalArgumentException> { shell().execute(call("true", "BAD-NAME" to reference())) }
    }

    @Test
    fun aSecretIsUsableOnlyInItsOwnAgentAndSession() = runTest {
        val ref = reference()
        val other = call("echo \$TOKEN", "TOKEN" to ref)
        assertFailsWith<IllegalStateException> { execTools(backend, secrets, SecretScope("ada", "s2"), "ada").first().execute(other) }
        assertFailsWith<IllegalStateException> { execTools(backend, secrets, SecretScope("bob", "s1"), "bob").first().execute(other) }
        assertFailsWith<IllegalStateException> { shell(scope = null).execute(other) }
        assertFalse(secrets.redacting(shell()).execute(call("echo \$TOKEN", "TOKEN" to ref)).contains(value))
    }
}
