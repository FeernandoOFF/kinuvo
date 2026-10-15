package kinuvo.cli

import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.context
import kinuvo.harness.server.Harness
import kinuvo.harness.server.HarnessServer
import kinuvo.client.KinuvoClient
import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.core.PrivateFile
import kinuvo.harness.codex.CodexProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CliTest {
    private val home = Files.createTempDirectory("harness-cli").toFile()

    @Test
    fun managesTheServerFromTheCommandLine() = runBlocking {
        val server = HarnessServer(Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json")))))))
        val port = server.start("127.0.0.1", 0, schedule = false)
        val access = object : ServerAccess {
            override suspend fun <T> use(block: suspend (KinuvoClient) -> T): T {
                val client = KinuvoClient("ws://127.0.0.1:$port/ws", "cli", server.auth.sessionToken(), timeZone = "UTC")
                client.connect()
                return try { block(client) } finally { client.close() }
            }
        }
        suspend fun kinuvo(vararg args: String): String {
            val out = StringBuilder()
            Kinuvo(access).context { echoMessage = { _, message, _, _ -> out.append(message).append('\n') } }.parse(args.toList())
            return out.toString()
        }
        try {
            kinuvo("agents", "create", "work", "--name", "Work")
            assertContains(kinuvo("profile", "list").lines().map { it.split(Regex("\\s+")).take(2) }, listOf("work", "Work"))
            val created = kinuvo("-a", "work", "cron", "create", "Brief", "Summarize", "--every", "2h", "--approvals", "deny")
            val id = Regex("Created routine (\\w+)").find(created)!!.groupValues[1]
            assertContains(kinuvo("-a", "work", "routines", "pause", id), "paused")
            assertEquals(listOf(id, "Brief", "every", "2h", "paused", "-"), kinuvo("-a", "work", "routines", "list").lines().first { id in it }.split(Regex("\\s+")))
            kinuvo("-a", "work", "mcp", "add", "fs", "--command", "npx", "--include", "read", "--", "-y", "server", "/tmp")
            assertEquals("fs agent stdio - npx -y server /tmp", kinuvo("-a", "work", "mcp", "ls").lines()[1].split(Regex("\\s+")).joinToString(" "))
            kinuvo("-a", "work", "skills", "create", "notes", "--description", "Take notes", "--body", "Write them down", "--shared")
            assertContains(kinuvo("-a", "work", "skills", "show", "notes"), "Write them down")
            assertFailsWith<CliktError> { kinuvo("-a", "work", "skills", "remove", "nope") }
            kinuvo("agents", "delete", "work", "--yes")
            assertEquals(listOf("default"), server.harness.store.agents().map { it.id })
            val pair = kinuvo("pair", "--host", "192.168.1.12", "--refresh")
            assertContains(pair, "kinuvo://pair?server=http%3A%2F%2F192.168.1.12%3A$port&code=")
            assertContains(pair, "/#pair=")
            val phone = KinuvoClient("ws://127.0.0.1:$port/ws", "phone", pairingCode = server.auth.currentPairingCode())
            try {
                phone.connect()
                assertContains(kinuvo("devices"), "phone")
                assertContains(kinuvo("devices", "revoke", "phone"), "Revoked phone")
                assertEquals(emptyList(), server.auth.devices())
            } finally { phone.close() }
        } finally {
            server.stop()
        }
    }

    @Test
    fun rendersServiceFiles() {
        val spec = ServiceSpec("/jdk/bin/java", "/h/.kinuvo/bin/kinuvo.jar", "0.0.0.0", 8787, "/h/.kinuvo/logs/server.log", mapOf("PATH" to "/usr/bin:/opt/homebrew/bin"))
        val plist = Launchd(home).render(spec)
        assertContains(plist, "<string>/h/.kinuvo/bin/kinuvo.jar</string>")
        assertContains(plist, "<key>PATH</key><string>/usr/bin:/opt/homebrew/bin</string>")
        assertContains(plist, "<key>KeepAlive</key><true/>")
        val unit = Systemd(home).render(spec)
        assertContains(unit, "ExecStart=\"/jdk/bin/java\" \"-jar\" \"/h/.kinuvo/bin/kinuvo.jar\" \"serve\" \"--host\" \"0.0.0.0\" \"--port\" \"8787\"")
        assertContains(unit, "Restart=always")
        assertFalse("--computer" in spec.command)
        assertContains(Launchd(home).render(spec.copy(computer = true)), "<string>--computer</string>")
        assertContains(Systemd(home).render(spec.copy(computer = true)), "\"--computer\"")
    }
}
