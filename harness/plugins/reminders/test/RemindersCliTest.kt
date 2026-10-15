package kinuvo.harness.reminders

import kinuvo.harness.core.tasks.TaskChange
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class RemindersCliTest {
    private class Cli : AutoCloseable {
        val root = Files.createTempDirectory("reminders-cli-test").toFile()
        val response = File(root, "response")
        val args = File(root, "args")
        val code = File(root, "code").apply { writeText("0") }
        val executable = File(root, "remindctl").apply {
            writeText("""#!/bin/sh
                printf '%s\n' "${'$'}@" > "${'$'}0.args"
                cat "${'$'}0.response"
                exit "${'$'}(cat "${'$'}0.code")"
            """.trimIndent().replace("\"${'$'}0.args\"", "\"${args.path}\"").replace("\"${'$'}0.response\"", "\"${response.path}\"").replace("\"${'$'}0.code\"", "\"${code.path}\""))
            setExecutable(true)
        }
        val provider = RemindersCli(executable.path, "Test Launcher")
        override fun close() { root.deleteRecursively() }
    }

    @Test
    fun requestsPermissionAndReportsDenial() = runTest {
        Cli().use { cli ->
            cli.response.writeText("""{"status":"authorized","authorized":true}""")
            cli.provider.authorize()
            assertEquals(listOf("authorize", "--json"), cli.args.readLines())
            cli.response.writeText("""{"status":"denied","authorized":false}""")
            cli.code.writeText("1")
            val error = assertFailsWith<IllegalStateException> { cli.provider.authorize() }.message!!
            assertTrue(error.contains("System Settings"))
            assertTrue(error.contains("Test Launcher"))
            assertTrue(error.contains("another terminal"))
            cli.response.writeText("Reminders access denied. Run remindctl authorize")
            assertTrue(assertFailsWith<IllegalStateException> { cli.provider.lists() }.message!!.contains("Test Launcher"))
        }
    }

    @Test
    fun mapsListsTasksAndSafeArguments() = runTest {
        Cli().use { cli ->
            cli.response.writeText("""[{"id":"list-1","title":"Errands"}]""")
            assertEquals("Errands", cli.provider.lists().single().name)
            cli.response.writeText("[$TASK]")
            val task = cli.provider.tasks("list-1").single()
            assertEquals("list-1", task.listId)
            assertEquals("Task", task.title)
            assertFalse(task.done)
            assertEquals(listOf("show", "open", "--list-id", "list-1", "--json"), cli.args.readLines())
            assertEquals(task, cli.provider.task("task-1"))
            assertNull(cli.provider.task("gone"))
            cli.response.writeText(TASK)
            cli.provider.create("list-1", "$(echo title)", "Notes", LocalDate(2026, 10, 4))
            assertEquals(listOf("add", "--title", "$(echo title)", "--list-id", "list-1", "--notes", "Notes", "--due", "2026-10-04", "--json"), cli.args.readLines())
            cli.provider.update("task-1", TaskChange(notes = "", clearDue = true, done = false))
            assertEquals(listOf("edit", "task-1", "--notes", "", "--clear-due", "--incomplete", "--json"), cli.args.readLines())
        }
    }

    @Test
    fun reportsMissingInstallationAndCommandFailures() = runTest {
        assertTrue(assertFailsWith<IllegalStateException> { RemindersCli("/nonexistent/remindctl").lists() }.message!!.contains("brew install"))
        Cli().use { cli ->
            cli.code.writeText("1")
            cli.response.writeText("Reminders permission denied")
            assertEquals("Reminders permission denied", assertFailsWith<IllegalStateException> { cli.provider.lists() }.message)
        }
    }

    companion object {
        private const val TASK = """{"id":"task-1","listID":"list-1","title":"Task","notes":"Notes","isCompleted":false}"""
    }
}
