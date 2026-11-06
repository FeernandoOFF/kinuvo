package kinuvo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import kinuvo.ui.design.Composer
import kinuvo.ui.design.HarnessTheme
import kinuvo.ui.design.StreamingMarkdown
import kinuvo.ui.design.usesCommandKey
import kinuvo.ui.e2e.runPhoneTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MarkdownComposerTest {
    @Test fun sendsMarkdownSourceAndClears() = runPhoneTest {
        val state = TextFieldState()
        var sent = ""
        setContent { HarnessTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Composer(false, { sent = it }, {}, state = state) } } }
        onNodeWithContentDescription("Message").performTextInput(" **bold** and `code` ")
        onNodeWithContentDescription("Send").performClick()
        runOnIdle {
            assertEquals("**bold** and `code`", sent)
            assertEquals("", state.text.toString())
        }
    }

    @Test fun returnContinuesAndEndsTaskList() = runPhoneTest {
        val state = TextFieldState("- [x] Done", TextRange(10))
        setContent { HarnessTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Composer(false, {}, {}, state = state) } } }
        val input = onNodeWithContentDescription("Message")
        input.performTextInput("\n")
        runOnIdle { assertEquals("- [x] Done\n- [ ] ", state.text.toString()) }
        input.performTextInput("\n")
        runOnIdle { assertEquals("- [x] Done\n", state.text.toString()) }
    }

    @Test fun pasteDoesNotContinueList() = runPhoneTest {
        val state = TextFieldState()
        setContent { HarnessTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Composer(false, {}, {}, state = state) } } }
        onNodeWithContentDescription("Message").performTextInput("- First\n")
        runOnIdle { assertEquals("- First\n", state.text.toString()) }
    }

    @Test fun selectionFormatsMarkdownSource() = runPhoneTest {
        val state = TextFieldState("hello", TextRange(5))
        setContent { HarnessTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Composer(false, {}, {}, state = state) } } }
        onNodeWithContentDescription("Message").performClick().performTextInputSelection(TextRange(0, 5))
        onNode(hasContentDescription("Bold", substring = true)).performClick()
        runOnIdle { assertEquals("**hello**", state.text.toString()) }
    }

    @Test fun shiftEnterContinuesListAndEnterSends() = runPhoneTest {
        val state = TextFieldState("1. First", TextRange(8))
        var sent = ""
        setContent { HarnessTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Composer(false, { sent = it }, {}, state = state) } } }
        val input = onNodeWithContentDescription("Message")
        input.performClick().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.ShiftLeft)
        }
        runOnIdle { assertEquals("1. First\n2. ", state.text.toString()) }
        input.performTextInput("Second")
        input.performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals("1. First\n2. Second", sent) }
    }

    @Test fun shortcutsToggleInlineFormatting() = runPhoneTest {
        val state = TextFieldState("hello", TextRange(5))
        setContent { HarnessTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Composer(false, {}, {}, state = state) } } }
        val input = onNodeWithContentDescription("Message")
        input.performClick().performTextInputSelection(TextRange(0, 5))
        val modifier = if (usesCommandKey) Key.MetaLeft else Key.CtrlLeft
        for ((key, marker) in listOf(Key.B to "**", Key.I to "*", Key.E to "`")) {
            input.performKeyInput { keyDown(modifier); pressKey(key); keyUp(modifier) }
            runOnIdle { assertEquals("${marker}hello$marker", state.text.toString()) }
            input.performKeyInput { keyDown(modifier); pressKey(key); keyUp(modifier) }
            runOnIdle { assertEquals("hello", state.text.toString()) }
        }
    }

    @Test fun streamingAppendsSuffixAndReplacesChangedSource() = runPhoneTest {
        var text by mutableStateOf("Hello")
        setContent { HarnessTheme { StreamingMarkdown(text) } }
        onNodeWithText("Hello").assertExists()
        runOnIdle { text = "Hello **world**" }
        onNodeWithText("Hello world").assertExists()
        runOnIdle { text = "**Changed**" }
        onNodeWithText("Changed").assertExists()
        onNodeWithText("Hello world").assertDoesNotExist()
    }

}
