package kinuvo.ui.rooms

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.trigger.Trigger
import kinuvo.app.session.*
import kinuvo.app.rooms.displayTitle
import kinuvo.protocol.RoomSession
import kinuvo.protocol.AgentInfo
import kotlin.test.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalRichTextApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomMentionsTest {
    @Test fun acceptedMentionsRoundTripDeleteAndUndoWithStableIdentity() {
        val state = RichTextState().apply { registerTrigger(Trigger(RoomMemberTrigger, '@')) }
        state.addTextAfterSelection("Ask @At")
        state.insertToken(RoomMemberTrigger, "atlas-2", "@Atlas Smith")
        state.addTextAfterSelection("to review with @P")
        state.insertToken(RoomMemberTrigger, "pip", "@Pip")
        val draft = state.roomDraft(listOf("atlas-2", "pip"))
        assertEquals(listOf("atlas-2", "pip"), draft.recipients)
        assertEquals(listOf("pip"), state.roomDraft(listOf("pip")).recipients)
        val restored = RichTextState().apply { setMarkdown(draft.text) }
        assertEquals(draft, restored.roomDraft(listOf("atlas-2", "pip")))
        state.replaceTextRange(TextRange(4, 16), "")
        assertEquals(listOf("pip"), state.roomDraft(listOf("atlas-2", "pip")).recipients)
        state.history.undo()
        assertEquals(draft, state.roomDraft(listOf("atlas-2", "pip")))
    }

    @Test fun sameLabelIdentityReplacementAndRestorationEmitStructuralDrafts() = kotlinx.coroutines.test.runTest {
        val state = RichTextState().apply { setMarkdown("[@Atlas](trigger:room-member:a)") }
        val visible = Triple(state.annotatedString, state.selection, state.activeTriggerQuery)
        val drafts = mutableListOf<kinuvo.app.rooms.RoomDraft>()
        val observer = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            androidx.compose.runtime.snapshotFlow { state.annotatedString to state.roomDraft(listOf("a", "b")) }.collect { drafts += it.second }
        }
        state.setRichTextDocument(RichTextState().apply { setMarkdown("[@Atlas](trigger:room-member:b)") }.toRichTextDocument(), state.selection)
        assertEquals(visible, Triple(state.annotatedString, state.selection, state.activeTriggerQuery))
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        testScheduler.runCurrent()
        assertEquals(listOf("b"), drafts.last().recipients)
        state.setMarkdown("[@Atlas](trigger:room-member:a)")
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        testScheduler.runCurrent()
        assertEquals(listOf("a"), drafts.last().recipients)
        state.registerTrigger(Trigger(RoomMemberTrigger, '@'))
        state.setText("")
        state.addTextAfterSelection("@At")
        state.insertToken(RoomMemberTrigger, "a", "@Atlas")
        val beforeEdit = Triple(state.annotatedString, state.selection, state.activeTriggerQuery)
        state.replaceTextRange(TextRange(0, state.annotatedString.length), "@At")
        state.insertToken(RoomMemberTrigger, "b", "@Atlas")
        assertEquals(beforeEdit, Triple(state.annotatedString, state.selection, state.activeTriggerQuery))
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        testScheduler.runCurrent()
        assertEquals(listOf("b"), drafts.last().recipients)
        repeat(3) { if (state.roomDraft(listOf("a", "b")).recipients != listOf("a")) state.history.undo() }
        assertEquals(beforeEdit, Triple(state.annotatedString, state.selection, state.activeTriggerQuery))
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        testScheduler.runCurrent()
        assertEquals(listOf("a"), drafts.last().recipients)
        observer.cancel()
    }

    @Test fun generatedSessionTitlesShowMentionLabelsWithoutEditorMetadata() {
        assertEquals("@Atlas @Pip Review the plan", RoomSession("s", "r", title = "[@Atlas](trigger:room-member:atlas) [@Pip](trigger:room-member:pip) Review the plan").displayTitle())
        assertEquals("@Atlas", RoomSession("s", "r", title = "[@Atlas](trigger:room-member:atlas-truncated").displayTitle())
        val prompt = "Hey guys, what is your name and what tools do you have available?"
        assertEquals(prompt, RoomSession("s", "r", listOf(kinuvo.protocol.RoomMessage("m", text = prompt))).displayTitle())
    }

    @Test fun plainNamesDoNotTargetAndDuplicateNamesHaveDistinctChoices() {
        val agents = listOf(AgentInfo("a", "Atlas", "local"), AgentInfo("b", "Atlas", "local"))
        val source = roomCompletions(listOf("a", "b"), agents)
        assertEquals(listOf("a", "b"), source.items.map { it.id })
        assertEquals(listOf("a", "b"), source.items.map { it.description })
        assertTrue(RichTextState().apply { setText("@Atlas please review") }.roomDraft(listOf("a", "b")).recipients.isEmpty())
        val first = RichTextState().apply { setMarkdown("[@Atlas](trigger:room-member:a)") }.roomDraft(listOf("a", "b"))
        val second = RichTextState().apply { setMarkdown("[@Atlas](trigger:room-member:b)") }.roomDraft(listOf("a", "b"))
        assertNotEquals(first, second)
        assertEquals("/review ", findCompletion("/rev", 4, sources = listOf(CompletionSource('/', listOf(Completion("review")), startOnly = true)))!!.insertion(Completion("review")))
    }
}
