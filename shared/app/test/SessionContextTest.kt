package kinuvo.app

import kinuvo.app.session.*
import kinuvo.protocol.*
import kotlin.test.*

class SessionContextTest {
    private val direct = SessionContextScope("iris", "session")
    private val room = SessionContextScope(roomId = "room", roomSessionId = "public", memberAgentIds = listOf("iris", "milo"))
    private val computer = ComputerInfo("computer", "Computer", "macOS", true, ComputerControl.Agent, agentId = "iris", sessionId = "session")

    @Test fun computerPreviewRequiresActiveExactAttribution() {
        assertTrue(direct.contains(computer, "iris"))
        assertFalse(direct.contains(computer.copy(control = ComputerControl.Idle), "iris"))
        assertFalse(direct.contains(computer.copy(agentId = null), null))
        assertFalse(direct.contains(computer.copy(sessionId = "other"), "iris"))
        assertFalse(direct.contains(computer.copy(roomSessionId = "public"), "iris"))
        assertFalse(SessionContextScope("iris").contains(computer, "iris"))
        assertTrue(direct.contains(computer.copy(control = ComputerControl.User), "iris"))
    }

    @Test fun roomUsesPublicSessionAndSelectedMemberRatherThanPrivateSessionId() {
        val active = computer.copy(sessionId = "private-run", roomSessionId = "public")
        assertTrue(room.contains(active, null))
        assertTrue(room.contains(active, "iris"))
        assertFalse(room.contains(active, "milo"))
        assertFalse(room.contains(active.copy(agentId = "outsider"), null))
        assertFalse(room.contains(active.copy(roomSessionId = "previous-public"), null))
        assertFalse(room.contains(active.copy(roomSessionId = null), null))
    }

    @Test fun artifactOutputsUseSavedAttributionAndExcludeOtherSessionsAndAgents() {
        fun artifact(id: String, agent: String, session: String?, public: String? = null) = ArtifactInfo(id, id, "html", agent, session, 1, 2, public)
        val all = listOf(artifact("direct", "iris", "session"), artifact("other", "iris", "old"),
            artifact("room-iris", "iris", "private-a", "public"), artifact("room-milo", "milo", "private-b", "public"),
            artifact("old-room", "iris", "private-a", "old-public"), artifact("outsider", "other", "private", "public"))
        assertEquals(listOf("direct"), direct.outputs(all, "iris").map { it.id })
        assertEquals(listOf("room-iris", "room-milo"), room.outputs(all, null).map { it.id })
        assertEquals(listOf("room-milo"), room.outputs(all, "milo").map { it.id })
        assertTrue(SessionContextScope("iris").outputs(all, "iris").isEmpty())
    }

    @Test fun executionDefaultResetPreservesModelAndApprovalSettings() {
        val saved = SessionSettings("model", "high", "strict", "provider", "ssh", listOf("/work"), ContainerSettings("custom", true), SshSettings("host"))
        assertEquals(SessionSettings("model", "high", "strict", "provider"), saved.agentExecutionDefault())
    }

    @Test fun backendValidationRequiresWorkspaceAndConfiguredConnectionWithoutChangingModel() {
        val defaults = AgentSettings("model", "medium", "strict", "none")
        validateSessionExecution(SessionSettings(), defaults)
        validateSessionExecution(SessionSettings(exec = "none"), defaults)
        assertFailsWith<IllegalArgumentException> { validateSessionExecution(SessionSettings(exec = "host"), defaults) }
        assertFailsWith<IllegalArgumentException> { validateSessionExecution(SessionSettings(exec = "ssh", workspace = listOf("/work")), defaults) }
        assertFailsWith<IllegalArgumentException> { validateSessionExecution(SessionSettings(exec = "ssh", workspace = listOf("/work"), ssh = SshSettings("host", port = 70000)), defaults) }
        validateSessionExecution(SessionSettings(exec = "host", workspace = listOf("/work")), defaults)
        validateSessionExecution(SessionSettings(exec = "container", workspace = listOf("/work")), defaults)
    }
}
