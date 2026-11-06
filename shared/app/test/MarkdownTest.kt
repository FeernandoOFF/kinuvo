package kinuvo.app

import kinuvo.app.markdown.*
import org.intellij.markdown.MarkdownElementTypes.CODE_SPAN
import org.intellij.markdown.MarkdownElementTypes.EMPH
import org.intellij.markdown.MarkdownElementTypes.INLINE_LINK
import org.intellij.markdown.MarkdownElementTypes.ORDERED_LIST
import org.intellij.markdown.MarkdownElementTypes.STRONG
import org.intellij.markdown.MarkdownElementTypes.UNORDERED_LIST
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes.STRIKETHROUGH
import kotlin.test.*

class MarkdownTest {
    /** `|` marks the cursor, or `«` and `»` the selection. */
    private class Buffer(marked: String) : MarkdownBuffer {
        private val chars = StringBuilder(marked.replace("|", "").replace("«", "").replace("»", ""))
        override val text: CharSequence get() = chars
        override var selectionStart = marked.indexOf('|').takeIf { it >= 0 } ?: marked.indexOf('«')
        override var selectionEnd = marked.indexOf('|').takeIf { it >= 0 } ?: (marked.indexOf('»') - 1)
        override fun replace(start: Int, end: Int, text: CharSequence) { chars.replace(start, end, text.toString()) }
        override fun select(start: Int, end: Int) { selectionStart = start; selectionEnd = end }
        override fun toString() = if (selectionStart == selectionEnd) chars.toString().let { it.substring(0, selectionStart) + "|" + it.substring(selectionStart) }
            else chars.toString().let { it.substring(0, selectionStart) + "«" + it.substring(selectionStart, selectionEnd) + "»" + it.substring(selectionEnd) }
    }

    private fun edited(marked: String, edit: MarkdownBuffer.() -> Unit) = Buffer(marked).apply(edit).toString()
    private fun spans(text: String) = markdownSpans(text).map { Triple(it.type.name, text.substring(it.start, it.end), it.isMarker) }

    @Test fun spansStyleInlineFormattingAndDimItsMarkers() = assertEquals(
        listOf(Triple("STRONG", "**hi**", false), Triple("CODE_SPAN", "`ls`", false), Triple("EMPH", "*", true), Triple("EMPH", "*", true),
            Triple("EMPH", "*", true), Triple("EMPH", "*", true), Triple("BACKTICK", "`", true), Triple("BACKTICK", "`", true)),
        spans("say **hi** `ls`"),
    )

    @Test fun spansMarkLinkSyntax() {
        val link = spans("see [docs](https://kinuvo.dev)")
        assertEquals(Triple("INLINE_LINK", "[docs](https://kinuvo.dev)", false), link.first())
        assertEquals(listOf("[", "]", "(", "https://kinuvo.dev", ")"), link.filter { it.third }.map { it.second })
    }

    @Test fun spansMarkListBullets() = assertEquals(listOf("- ", "- "), spans("- one\n- two").filter { it.first == "LIST_BULLET" }.map { it.second.take(2) })

    @Test fun unclosedFormattingIsPlainText() = assertEquals(emptyList(), spans("say **hi"))

    @Test fun loneStarInBoldIsText() = assertEquals(4, spans("**2 * 3**").count { it.third })

    @Test fun codeFencesHaveNoInlineSpans() = assertEquals(emptyList(), spans("```\n**not bold**\n```"))

    @Test fun toggleWrapsSelection() = assertEquals("say **«hi»**", edited("say «hi»") { toggle(STRONG) })

    @Test fun toggleAtCursorInsertsMarkers() = assertEquals("say `|`", edited("say |") { toggle(CODE_SPAN) })

    @Test fun toggleUnwrapsEnclosingFormatting() = assertEquals("say «hi» now", edited("say **«hi»** now") { toggle(STRONG) })

    @Test fun toggleUnwrapsAroundCursor() = assertEquals("say h|i", edited("say ~~h|i~~") { toggle(STRIKETHROUGH) })

    @Test fun cursorAfterFormattingIsOutsideIt() = assertEquals("**hi***|*", edited("**hi**|") { toggle(EMPH) })

    @Test fun activeTypesAtCursor() {
        assertEquals(setOf(STRONG, EMPH), Buffer("**a *b|* c**").activeTypes())
        assertEquals(setOf(UNORDERED_LIST), Buffer("- on|e").activeTypes())
        assertEquals(setOf(ORDERED_LIST, CODE_SPAN), Buffer("1. run `l|s`").activeTypes())
        assertEquals(emptySet(), Buffer("**hi**|").activeTypes())
    }

    @Test fun toggleListMakesItems() = assertEquals("- «one\n- two»", edited("«one\ntwo»") { toggleList(ordered = false) })

    @Test fun toggleListAtCursorChangesItsLine() = assertEquals("1. on|e\ntwo", edited("- on|e\ntwo") { toggleList(ordered = true) })

    @Test fun toggleListNumbersSelectedLines() = assertEquals("«1. one\n2. two»", edited("«- one\ntwo»") { toggleList(ordered = true) })

    @Test fun toggleListRemovesItems() = assertEquals("«one\ntwo»", edited("- «one\n- two»") { toggleList(ordered = false) })

    @Test fun continuesBullets() = assertEquals("- one\n- |", edited("- one\n|") { assertTrue(continueList()) })

    @Test fun continuesNumbersAndIndent() = assertEquals("  9) one\n  10) |", edited("  9) one\n|") { assertTrue(continueList()) })

    @Test fun continuesTasksUnchecked() = assertEquals("- [x] done\n- [ ] |", edited("- [x] done\n|") { assertTrue(continueList()) })

    @Test fun emptyItemEndsTheList() = assertEquals("- one\n|", edited("- one\n- \n|") { assertTrue(continueList()) })

    @Test fun plainLinesDontContinue() = assertEquals("one\n|", edited("one\n|") { assertFalse(continueList()) })

    @Test fun codeFenceLinesDontContinue() = assertEquals("```\n- one\n|", edited("```\n- one\n|") { assertFalse(continueList()) })

    @Test fun markerTokensAreTheParsers() = assertTrue(markdownSpans("*a*").all { it.type == EMPH || it.type == MarkdownTokenTypes.EMPH })

    @Test fun linksAreStyled() = assertTrue(markdownSpans("[a](b)").any { it.type == INLINE_LINK })
}
