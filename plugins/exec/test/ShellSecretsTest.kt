package kinuvo.plugins.exec

import kinuvo.server.plugin.SecretResolver
import kinuvo.testing.harness.TemporaryDirectories
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShellSecretsTest {
    private val temporary = TemporaryDirectories()
    private val secrets = object : SecretResolver {
        override suspend fun resolve(uri: String, reason: String) = if (uri == "pass://Work/GitHub/token") "ghp_value" else error("Can't read $uri")
    }
    private val shell = ShellTool(HostBackend(listOf(temporary.create("ws").toFile())), secrets)

    @AfterTest fun cleanUp() = temporary.close()

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test fun setsEachReferenceAsAnEnvironmentVariable() = runTest {
        assertEquals("[exit code 0]\n9", shell.execute(args("""{"command":"printf %s \"${'$'}{#TOKEN}\"","secrets":{"TOKEN":"${'$'}{pass://Work/GitHub/token}"}}""")))
    }

    @Test fun refusesAnythingButReferencesInForegroundCommands() = runTest {
        val refused = listOf(
            """{"command":"echo ${'$'}{pass://Work/GitHub/token}"}""",
            """{"command":"true","secrets":{"TOKEN":"ghp_value"}}""",
            """{"command":"true","secrets":{"TOKEN":"${'$'}{HOME}"}}""",
            """{"command":"true","secrets":{"1BAD":"${'$'}{pass://Work/GitHub/token}"}}""",
            """{"command":"true","secrets":"${'$'}{pass://Work/GitHub/token}"}""",
            """{"command":"true","background":true,"secrets":{"TOKEN":"${'$'}{pass://Work/GitHub/token}"}}""",
            """{"command":"true","watch":true,"secrets":{"TOKEN":"${'$'}{pass://Work/GitHub/token}"}}""",
        )
        refused.forEach { assertFailsWith<IllegalArgumentException>(it) { shell.bind(args(it)) } }
    }
}
