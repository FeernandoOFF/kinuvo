package kinuvo.harness.exec

import kinuvo.harness.core.ApprovalPolicy
import kinuvo.harness.core.Verdict
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.FileTrustRules
import kinuvo.harness.core.store.FileStore
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kinuvo.harness.core.ownedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ShellApprovalsTest {
    @Test
    fun alwaysAllowSavesCommandKeys(): Unit = runBlocking {
        val files = AgentFiles(Path(Files.createTempDirectory("harness-test").toFile().path))
        val store = FileStore(files)
        val base = store.agents().single()
        val policy = ApprovalPolicy(base, FileTrustRules(files))
        val shell = kinuvo.harness.core.PluginTools(listOf(ShellTool(HostBackend(listOf(File(files.agent(base.id).toString()))))))
            .ownedBy(kinuvo.protocol.PluginInstanceId("kinuvo.exec")).tools.single()
        val args = """{"command":"./kotlin test -m core"}"""

        assertEquals(Verdict.Run, policy.decide(shell, """{"command":"git status && ls"}"""))
        val ask = assertIs<Verdict.Ask>(policy.decide(shell, args))
        assertEquals(listOf("./kotlin test"), ask.trustKeys)
        policy.trust(shell, ask.trustKeys)
        assertEquals(Verdict.Run, policy.decide(shell, """{"command":"./kotlin test -m desktop && git log"}"""))
        assertIs<Verdict.Ask>(policy.decide(shell, """{"command":"./kotlin run"}"""))
        assertEquals(listOf("./kotlin test"), policy.rules().shell)
        assertIs<Verdict.Block>(policy.decide(shell, """{"command":"rm -rf ~"}"""))
    }
}
