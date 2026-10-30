package kinuvo.app

import kinuvo.app.agent.*
import kinuvo.app.session.*
import kinuvo.protocol.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ExecutionSessionStateTest {
    @Test fun implicitResumeDoesNotBypassClosedArchivedOrUnsupportedSessions() {
        val caps = SessionCapabilities(0, ServerCapabilities("server"), chat = CapabilityState.unavailable("Ended", "session_lost"), execution = ExecutionCapabilities(resume = true))
        assertTrue(resumeOnSend(ExecutionPhase.Lost, caps))
        assertFalse(resumeOnSend(ExecutionPhase.Lost, caps.copy(execution = ExecutionCapabilities(resume = false))))
        assertFalse(resumeOnSend(ExecutionPhase.Lost, caps.copy(chat = CapabilityState.unavailable("Closed", "session_closed"))))
        assertFalse(resumeOnSend(ExecutionPhase.Lost, caps.copy(chat = CapabilityState.unavailable("Archived", "archived"))))
        assertFalse(resumeOnSend(ExecutionPhase.Lost, caps.copy(chat = CapabilityState.unavailable("Revoked", "access_revoked"))))
    }

    @Test fun outputMediaSurvivesLiveCommitHistoryAndInterruptedDrafts() {
        val attachment = Attachment("file", "output.png", "image/png", 8)
        val result = SessionEntry.ToolResult("t", "Image", "", attachments = listOf(attachment))
        val call = SessionEntry.ToolCall("t", "Image", "{}")
        val live = SessionData("agent", live = LiveState(entries = listOf(call, result)))
        val item = assertIs<ChatItem.ToolCall>(live.items.single())
        assertEquals(listOf(attachment), item.attachments)
        val saved = live.copy(entries = listOf(call, result))
        assertEquals(listOf(item), saved.items)
        assertEquals(saved.items, SessionData("agent", entries = listOf(call, result)).items)
        assertEquals(listOf(attachment), assertIs<ChatItem.ToolCall>(SessionData("agent", entries = listOf(result)).items.single()).attachments)
        val notice = SessionEntry.Notice("n", "interrupted", "Interrupted", listOf(result))
        val nested = assertIs<ChatItem.Notice>(SessionData("agent", entries = listOf(notice)).items.single())
        assertEquals(listOf(attachment), assertIs<ChatItem.ToolCall>(nested.draft.single()).attachments)
    }

    @Test fun normalizedAnswersKeepDisplayValuesAndDoNotEncodeAcp() {
        val asked = AgentEvent.QuestionsAsked("q", "t", "Deploy?", listOf(Question("env", "Environment", QuestionKind.Single, listOf("Production"), default = listOf("Production"))))
        val prompt = ExecutionPrompt.Questions("q", asked, types = mapOf("env" to "string"), values = mapOf("env" to mapOf("Production" to "prod")))
        assertEquals("""{"answers":{"env":["Production"]}}""", questionsAnswer(prompt, mapOf("env" to listOf("Production"))).toString())
        assertEquals(JsonNull, questionsAnswer(prompt, null))
        assertEquals("""{"optionId":"once"}""", approvalAnswer("once").toString())
        assertEquals(JsonNull, approvalAnswer(null))
        assertEquals("""{"opened":true}""", openLinkAnswer(true).toString())
        assertSame(prompt, ExecutionRequest("q", ExecutionRequestKind.Question, JsonNull, prompt = prompt).displayPrompt())
        assertIs<ExecutionPrompt.Unsupported>(ExecutionRequest("x", ExecutionRequestKind.Question, buildJsonObject { put("mode", "form") }).displayPrompt())
    }

    @Test fun liveEntriesKeepKeysAcrossMutationAndCommitWithoutDuplicateRows() {
        val live = LiveState(executionPhase = ExecutionPhase.Running, entries = listOf(SessionEntry.AssistantMessage("Hello", "a")))
        val initial = SessionData("agent", live = live).items.single()
        val changed = live.on(AgentEvent.ExecutionEntryChanged(SessionEntry.AssistantMessage("Hello world", "a")))
        assertEquals(initial.id, SessionData("agent", live = changed).items.single().id)
        val saved = SessionEntry.AssistantMessage("Hello world", "a")
        assertEquals(listOf(ChatItem.Assistant(initial.id, "Hello world")), SessionData("agent", entries = listOf(saved), live = changed).items)
        val native = LiveState(messageId = "a", text = "Hello world")
        assertEquals(1, SessionData("agent", entries = listOf(saved), live = native).items.size)
    }

    @Test fun noticesKeepInterruptedEvidenceNestedAndDoNotClaimItsIdentity() {
        val draft = SessionEntry.AssistantMessage("Partial", "a")
        val notice = SessionEntry.Notice("n", "interrupted", "Connection lost", listOf(draft))
        val data = SessionData("agent", entries = listOf(notice), live = LiveState(entries = listOf(draft)))
        val row = assertIs<ChatItem.Notice>(data.items.single())
        assertEquals("interrupted", row.code)
        assertEquals("Partial", assertIs<ChatItem.Assistant>(row.draft.single()).text)
        val next = data.copy(entries = listOf(notice, SessionEntry.AssistantMessage("Recovered", "a"))).items
        assertEquals(2, next.size)
        assertEquals("Recovered", assertIs<ChatItem.Assistant>(next.last()).text)
    }

    @Test fun externalCapabilitiesAndArchiveDoNotOfferNativeFallback() {
        val server = ServerCapabilities("s", features = setOf(ServerFeature.Attachments))
        val caps = SessionCapabilities(server = server, chat = CapabilityState.unavailable("Saved history only", "archived"),
            attachments = mapOf("image/png" to CapabilityState()), execution = ExecutionCapabilities(supportedMimeTypes = setOf("image/png")))
        assertTrue(caps.attachmentInput("image/png").actionable)
        assertFalse(caps.attachmentInput("text/plain").actionable)
        val state = ChatState(agent = AgentInfo("a", "A", "external", executionKind = ExecutionKind.Acp), selected = "s", capabilities = caps, phase = ExecutionPhase.Lost)
        assertNull(state.ended)
        assertFalse(state.canStop)
        assertEquals("Saved history only", state.chatBlocked)
        assertTrue(readiness(caps.chat, "Harness", "Host").actions.isEmpty())
        assertNull(SessionEnd.of(ExecutionPhase.Idle, CapabilityState.unavailable("Offline", "host_offline"), true))
        assertTrue(requireNotNull(SessionEnd.of(ExecutionPhase.Lost, CapabilityState.unavailable("Closed", "session_closed"), false)).closed)
    }

    @Test fun orderingUsesCreationAndLegacyOrdinalAndKeepsFavorites() {
        val old = SessionInfo("z", "a", "Legacy", legacyOrder = 0, favorite = true)
        val newer = SessionInfo("a", "a", "New", createdAt = 20)
        val earlier = SessionInfo("b", "a", "Earlier", createdAt = 10)
        val sorted = listOf(old, earlier, newer).sortedWith(SessionOrder)
        assertEquals(listOf("a", "b", "z"), sorted.map { it.id })
        assertTrue(sorted.last().favorite)
    }
}
