package kinuvo.ui.design

import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InlineMarkdownTest {
    private fun typed(text: String, then: String = "") = RichTextState().apply {
        addTextAfterSelection(text)
        applyInlineMarkdown()
        if (then.isNotEmpty()) addTextAfterSelection(then)
    }

    @Test fun bold() = assertEquals("say **hi**", typed("say **hi**").toMarkdown().trim())

    @Test fun italic() = assertEquals("say *hi*", typed("say *hi*").toMarkdown().trim())

    @Test fun underscoreItalic() = assertEquals("say *hi*", typed("say _hi_").toMarkdown().trim())

    @Test fun code() = assertEquals("run `ls -la`", typed("run `ls -la`").toMarkdown().trim())

    @Test fun styleEndsAtMarker() = assertEquals("**hi** there", typed("**hi**", " there").toMarkdown().trim())

    @Test fun codeEndsAtMarker() = assertEquals("`ls` now", typed("`ls`", " now").toMarkdown().trim())

    @Test fun ignoresSnakeCase() = assertFalse(RichTextState().apply { addTextAfterSelection("foo_bar_") }.applyInlineMarkdown())

    @Test fun ignoresOpenBold() = assertFalse(RichTextState().apply { addTextAfterSelection("**hi*") }.applyInlineMarkdown())

    @Test fun ignoresSpacedStar() = assertFalse(RichTextState().apply { addTextAfterSelection("2 * 3 *") }.applyInlineMarkdown())
}
