package kinuvo.app

import kinuvo.app.screen.*
import kinuvo.protocol.screen.*
import kotlin.test.*

class RemoteKeyboardTest {
    @Test fun compositionIsCommittedOnceAndPastePreservesUnicode() {
        assertTrue(remoteTextEdit(RemoteInputAnchor + "に", true).actions.isEmpty())
        assertTrue(remoteTextEdit(RemoteInputAnchor + "日本", true).actions.isEmpty())
        val committed = remoteTextEdit(RemoteInputAnchor + "日本語 👋\npasted", false)
        assertEquals(listOf(ScreenAction(ScreenActionKind.Type, text = "日本語 👋\npasted")), committed.actions)
        assertEquals(RemoteInputAnchor, committed.text)
        assertTrue(remoteTextEdit(RemoteInputAnchor, false).actions.isEmpty())
    }
    @Test fun mobileDeletionAndCompositionCancellationDoNotDuplicateInput() {
        assertEquals(listOf(ScreenAction(ScreenActionKind.Key, key = "Backspace")), remoteTextEdit("", false).actions)
        assertTrue(remoteTextEdit(RemoteInputAnchor, false).actions.isEmpty())
        assertEquals(listOf(ScreenAction(ScreenActionKind.Type, text = "a")), remoteTextEdit(RemoteInputAnchor + "a", false).actions)
    }
}
