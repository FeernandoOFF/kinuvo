package kinuvo.server

import kinuvo.protocol.session.*
import kotlin.test.*
import kinuvo.harness.builtin.activeHistory

class VoiceCallHistoryTest {
    @Test fun expandsCallTurnsInModelHistoryAfterDelegation() {
        val task = SessionEntry.UserMessage("task", voice = true)
        val reply = SessionEntry.AssistantMessage("done")
        val call = SessionEntry.VoiceCall("call", listOf(VoiceTurn(true, "hello"), VoiceTurn(false, "hi")), 1, 20)
        assertEquals(listOf(task, reply, SessionEntry.UserMessage("hello", voice = true), SessionEntry.AssistantMessage("hi")), listOf(task, reply, call).activeHistory())
    }
}
