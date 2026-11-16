package kinuvo.cli

import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.context
import kinuvo.client.KinuvoClient
import kinuvo.testing.harness.TemporaryDirectories
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DiagnosticsCliTest {
    private val directories = TemporaryDirectories()
    @AfterTest fun close() = directories.close()

    @Test fun exportsWithoutOpeningAServer() = runBlocking {
        val home = directories.create("diagnostics-cli").toFile()
        File(home, "logs").mkdir()
        File(home, "logs/desktop-1.log").writeText("failure\npassword=PRIVATE_PASSWORD")
        val access = object : ServerAccess {
            override suspend fun <T> use(block: suspend (KinuvoClient) -> T): T = error("Diagnostics must not start or connect to a Server")
        }
        val previous = System.getProperty("kinuvo.home")
        System.setProperty("kinuvo.home", home.path)
        try {
            val output = File(home, "diagnostics.zip")
            val messages = StringBuilder()
            Kinuvo(access).context { echoMessage = { _, message, _, _ -> messages.append(message) } }
                .parse(listOf("diagnostics", "export", "--output", output.path))
            assertContains(messages.toString(), "Review the ZIP")
            ZipFile(output).use { zip ->
                val logs = zip.getInputStream(zip.getEntry("this-device/logs/desktop-1.log")).bufferedReader().use { it.readText() }
                assertContains(logs, "failure")
                assertFalse("PRIVATE_PASSWORD" in logs)
            }
        } finally {
            if (previous == null) System.clearProperty("kinuvo.home") else System.setProperty("kinuvo.home", previous)
        }
    }
}
