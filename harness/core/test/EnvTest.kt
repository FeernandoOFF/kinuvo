package kinuvo.harness.core

import kinuvo.harness.core.store.loadEnv
import kinuvo.harness.core.store.writeAtomically
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EnvTest {
    private val home = TestHome()
    private val files = home.files

    @Test
    fun parsesCommentsExportAndQuotes() {
        val text = listOf(
            "# comment",
            "export TOKEN=abc",
            "PLAIN = value # trailing",
            "DOUBLE=\"a # b\\nc\"",
            "SINGLE='\${NOT_EXPANDED}'",
            "not a line",
        ).joinToString("\n")
        assertEquals(
            mapOf("TOKEN" to "abc", "PLAIN" to "value", "DOUBLE" to "a # b\nc", "SINGLE" to "\${NOT_EXPANDED}"),
            parseDotEnv(text),
        )
    }

    @Test
    fun agentFileOverridesSharedAndProcessWins() = runTest {
        writeAtomically(files.sharedEnv, "A=shared\nB=shared\nC=shared")
        writeAtomically(files.env(home.agent.id), "B=agent\nC=agent")
        val env = files.loadEnv(home.agent.id, process = mapOf("C" to "process"))
        assertEquals(mapOf("A" to "shared", "B" to "agent", "C" to "process"), env)
        assertEquals("shared/agent/process", Secrets().expand("\${A}/\${B}/\${C}", env, "test"))
    }

    @Test
    fun missingFilesAreEmpty() {
        assertEquals(mapOf("X" to "1"), files.loadEnv(home.agent.id, process = mapOf("X" to "1")))
    }
}
