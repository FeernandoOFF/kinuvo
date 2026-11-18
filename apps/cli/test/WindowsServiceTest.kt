package kinuvo.cli

import kinuvo.testing.harness.TemporaryDirectories
import kotlin.test.*
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

class WindowsServiceTest {
    @Test
    fun taskPreservesArgumentsAndUsesLimitedLogon() {
        TemporaryDirectories().use { temporary ->
            val home = temporary.create("windows-service").toFile()
            val calls = mutableListOf<List<String>>()
            val manager = WindowsService(home, "DOMAIN\\owner") { calls += it; Sh(0, "\"HostName\",\"TaskName\",\"Next Run Time\",\"Status\"\n\"host\",\"Kinuvo\\Server\",\"N/A\",\"Running\"") }
            val spec = ServiceSpec("C:\\Program Files\\Java\\java.exe", "C:\\Owner's files\\kinuvo.jar", "127.0.0.1", 8791,
                "$home/logs/server.log", mapOf("KINUVO_PUBLIC_URL" to "https://host/?a=1&b=2"), computer = true)
            val rendered = manager.render(spec)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rendered.byteInputStream())
            fun value(name: String) = document.getElementsByTagName(name).item(0).textContent
            assertEquals("InteractiveToken", value("LogonType"))
            assertEquals("LeastPrivilege", value("RunLevel"))
            assertEquals("PT0S", value("ExecutionTimeLimit"))
            assertEquals("true", value("Hidden"))
            assertEquals("PT1M", value("Interval"))
            val script = String(Base64.getDecoder().decode(value("Arguments").substringAfter("-EncodedCommand ")), Charsets.UTF_16LE)
            assertContains(script, "'C:\\Owner''s files\\kinuvo.jar'")
            assertContains(script, "'C:\\Program Files\\Java\\java.exe'")
            assertContains(script, "'--port' '8791'")
            assertContains(script, "'--computer'")
            assertContains(script, "\$env:KINUVO_HOME = '$home'")
            assertContains(script, "service-output.log' 2>&1")
            manager.install(spec)
            assertEquals(listOf("schtasks", "/Create", "/XML", manager.file.path, "/F", "/TN", WindowsService.TASK), calls.first())
            assertEquals(listOf("schtasks", "/Run", "/TN", WindowsService.TASK), calls.last())
            assertEquals("Running", manager.state())
            assertContains(calls.last(), "CSV")
            manager.uninstall()
            assertEquals(listOf("schtasks", "/Delete", "/F", "/TN", WindowsService.TASK), calls.last())
            assertFalse(manager.file.exists())
        }
    }
}
