package kinuvo.harness.server

import kinuvo.protocol.PendingInteraction
import kinuvo.protocol.PluginInstanceId
import kinuvo.protocol.ToolIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationTextTest {
    private fun approval(tool: String, args: String, plugin: String = "kinuvo.mcp", reason: String? = null) =
        PendingInteraction.Approval("a1", "c1", tool, args, reason = reason, identity = ToolIdentity(PluginInstanceId(plugin), tool))

    @Test
    fun shellApprovalsShowTheCommand() {
        val event = approval("shell", """{"command":"git push origin main\necho done","timeout":30}""", "kinuvo.exec", "Pushes to a remote")
        assertEquals("Kai wants to run a command" to "git push origin main\nPushes to a remote", approvalText("Kai", event))
    }

    @Test
    fun otherApprovalsShowTheToolAndItsMainArgument() {
        assertEquals("Kai needs an Approval" to "Allow write file: notes/todo.md", approvalText("Kai", approval("write_file", """{"path":"notes/todo.md","content":"x"}""")))
        assertEquals("Kai needs an Approval" to "Allow delete?", approvalText("Kai", approval("delete", """{"id":4}""")))
        assertEquals("Kai needs an Approval" to "Allow send: Sends email", approvalText("Kai", approval("send", "not json", reason = "Sends email")))
    }

    @Test
    fun markdownBecomesPlainText() {
        assertEquals("Done\nSee the docs for why.\nrun_code and snake_case stay",
            plainText("## Done\n\nSee [the docs](https://x.dev) for **why**.\n\n- `run_code` and snake_case stay"))
        assertEquals("val x = 1", plainText("```kotlin\nval x = 1\n```"))
    }
}
