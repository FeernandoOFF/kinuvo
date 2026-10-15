package kinuvo.ui

import kinuvo.ui.session.*

import kotlin.test.Test
import kotlin.test.assertEquals

class LineDiffTest {
    @Test fun additionsOnly() = assertEquals(listOf(true to "", true to "c"), lineDiff("a\nb", "a\nb\n\nc"))

    @Test fun replacedLine() = assertEquals(listOf(false to "b", true to "B"), lineDiff("a\nb\nc", "a\nB\nc"))

    @Test fun unchanged() = assertEquals(emptyList(), lineDiff("a\nb\n", "a\nb"))

    @Test fun firstEntry() = assertEquals(listOf(true to "- Use examples"), lineDiff("", "- Use examples\n"))

    @Test fun lastEntryRemoved() = assertEquals(listOf(false to "- Likes tea"), lineDiff("- Likes tea\n", ""))
}
