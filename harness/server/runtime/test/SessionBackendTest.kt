package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.*
import kinuvo.harness.testing.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionBackendTest {
    @Test fun directSettingsApplyToNextRunAndResetWithoutChangingAgent() = runTest {
        val fixture = RuntimeFixture()
        fixture.execOptions = setOf("host", "container", "ssh")
        val seen = mutableListOf<AgentConfig>()
        val harness = HarnessRuntime(fixture.dependencies, modelFactory = { config ->
            seen += config
            scriptedModel { _, _ -> flowOf(StreamFrame.End()) }
        })
        try {
            val first = harness.newSession(fixture.agent)
            val second = harness.newSession(fixture.agent)
            val settings = SessionSettings(exec = "ssh", workspace = listOf("/remote"), ssh = SshSettings("example", "dev", 2222))
            harness.configure(fixture.agent, first.id, settings)
            harness.runner(fixture.agent, first.id)
            harness.runner(fixture.agent, second.id)
            assertEquals("ssh", seen[0].exec)
            assertEquals(listOf("/remote"), seen[0].workspace)
            assertEquals(2222, seen[0].ssh?.port)
            assertEquals("none", seen[1].exec)
            assertEquals("none", fixture.agent.config.exec)
            // Settings are read from the persisted transcript by a fresh runtime too.
            val reopened = fixture.runtime(scriptedModel { _, _ -> flowOf(StreamFrame.End()) })
            try { assertEquals("ssh", reopened.capabilities(fixture.agent, first.id, null, ServerCapabilities("test")).effective?.exec) }
            finally { reopened.close() }
            harness.configure(fixture.agent, first.id, SessionSettings())
            assertEquals("none", harness.capabilities(fixture.agent, first.id, null, ServerCapabilities("test")).effective?.exec)
            assertEquals(SessionSettings(), fixture.store.session(fixture.agent, first.id).settings)
        } finally { harness.close() }
    }

    @Test fun backendValidationRejectsUnsupportedAndIncompleteConfigurations() = runTest {
        val fixture = RuntimeFixture()
        val harness = fixture.runtime()
        try {
            assertFailsWith<IllegalArgumentException> { harness.validateSettings(fixture.agent, SessionSettings(exec = "host", workspace = listOf("/work"))) }
            fixture.execOptions = setOf("host", "container", "ssh")
            assertFailsWith<IllegalArgumentException> { harness.validateSettings(fixture.agent, SessionSettings(exec = "host")) }
            assertFailsWith<IllegalArgumentException> { harness.validateSettings(fixture.agent, SessionSettings(exec = "host", workspace = listOf("relative"))) }
            assertFailsWith<IllegalArgumentException> { harness.validateSettings(fixture.agent, SessionSettings(exec = "ssh", workspace = listOf("/work"))) }
            assertFailsWith<IllegalArgumentException> { harness.validateSettings(fixture.agent, SessionSettings(exec = "ssh", workspace = listOf("/work"), ssh = SshSettings("-bad"))) }
            assertFailsWith<IllegalArgumentException> { harness.validateSettings(fixture.agent, SessionSettings(exec = "ssh", workspace = listOf("/work"), ssh = SshSettings("host", port = 0))) }
            assertFailsWith<IllegalArgumentException> { harness.validateSettings(fixture.agent, SessionSettings(exec = "ssh", workspace = listOf("~/work"), ssh = SshSettings("host"))) }
            assertEquals("none", harness.validateSettings(fixture.agent, SessionSettings(exec = "none")).exec)
        } finally { harness.close() }
    }

    @Test fun activeSnapshotStaysFrozenWhenNextRunSettingsChange() = runTest {
        val fixture = RuntimeFixture()
        fixture.execOptions = setOf("host")
        val started = CompletableDeferred<Unit>()
        val harness = fixture.runtime(scriptedModel { _, _ -> flow {
            started.complete(Unit)
            awaitCancellation()
        } })
        val runs = Runs(harness.log, backgroundScope)
        try {
            val session = harness.newSession(fixture.agent)
            harness.configure(fixture.agent, session.id, SessionSettings(exec = "host", workspace = listOf("/work")))
            runs.start(fixture.agent, session.id, "work") { harness.runner(fixture.agent, session.id) }
            runCurrent()
            assertTrue(started.isCompleted)
            harness.configure(fixture.agent, session.id, SessionSettings(exec = "none"))
            val capabilities = harness.capabilities(fixture.agent, session.id, null, ServerCapabilities("test"))
            assertEquals("host", capabilities.active?.exec)
            assertEquals(listOf("/work"), capabilities.active?.workspace)
            assertEquals("none", capabilities.effective?.exec)
            runs.stop(session.id)
            runCurrent()
            assertNull(harness.capabilities(fixture.agent, session.id, null, ServerCapabilities("test")).active)
        } finally { runs.stopAll(); harness.close() }
    }
}
