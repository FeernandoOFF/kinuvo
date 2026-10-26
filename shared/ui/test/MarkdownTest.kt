package kinuvo.ui

import kinuvo.ui.design.*

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    @Test fun paragraphsAreSeparateBlocks() = assertEquals(
        listOf(MdBlock.Prose("One."), MdBlock.Prose("Two\nlines.")),
        parseBlocks("One.\n\nTwo\nlines."),
    )

    @Test fun listsStayInOneBlock() = assertEquals(
        listOf(MdBlock.Prose("Steps:\n\n1. First\n\n2. Second\n\n   More"), MdBlock.Prose("Done.")),
        parseBlocks("Steps:\n\n1. First\n\n2. Second\n\n   More\n\nDone."),
    )
}
