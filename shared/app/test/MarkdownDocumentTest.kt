package kinuvo.app

import kinuvo.app.markdown.*
import kotlin.test.*

class MarkdownDocumentTest {
    private fun blocks(source: String) = markdownDocument(source).blocks
    private fun inline(source: String) = assertIs<MarkdownBlock.Paragraph>(blocks(source).single()).children
    private fun text(value: String) = listOf(MarkdownInline.Text(value))

    @Test fun headings() {
        for (level in 1..6) assertEquals(MarkdownBlock.Heading(level, text("Title")), blocks("${"#".repeat(level)} Title #").single())
        assertEquals(MarkdownBlock.Heading(1, text("Title")), blocks("Title\n====").single())
        assertEquals(MarkdownBlock.Heading(2, text("Title")), blocks("Title\n----").single())
    }

    @Test fun paragraphs() = assertEquals(listOf(MarkdownBlock.Paragraph(text("one")), MarkdownBlock.Paragraph(text("two"))), blocks("one\n\ntwo"))
    @Test fun strong() = assertEquals(listOf(MarkdownInline.Strong(text("bold"))), inline("**bold**"))
    @Test fun emphasis() = assertEquals(listOf(MarkdownInline.Emphasis(text("italic"))), inline("*italic*"))
    @Test fun strikethrough() = assertEquals(listOf(MarkdownInline.Strikethrough(text("gone"))), inline("~~gone~~"))
    @Test fun nestedFormatting() = assertEquals(listOf(MarkdownInline.Emphasis(listOf(MarkdownInline.Strong(text("both"))))), inline("***both***"))
    @Test fun loneMarkerInsideFormatting() = assertEquals(listOf(MarkdownInline.Strong(text("2 * 3"))), inline("**2 * 3**"))

    @Test fun unorderedList() {
        val list = assertIs<MarkdownBlock.UnorderedList>(blocks("- one\n- two").single())
        assertEquals(listOf(MarkdownListItem(listOf(MarkdownBlock.Paragraph(text("one")))), MarkdownListItem(listOf(MarkdownBlock.Paragraph(text("two"))))), list.items)
    }

    @Test fun orderedListStartsAtItsNumber() {
        val list = assertIs<MarkdownBlock.OrderedList>(blocks("7. seven\n8. eight").single())
        assertEquals(7, list.start)
        assertEquals(2, list.items.size)
    }

    @Test fun nestedListsAndTasks() {
        val list = assertIs<MarkdownBlock.UnorderedList>(blocks("- [x] done\n  1. child\n- [ ] next\n- plain").single())
        assertEquals(listOf(true, false, null), list.items.map { it.checked })
        assertEquals(text("done"), assertIs<MarkdownBlock.Paragraph>(list.items[0].blocks[0]).children)
        val nested = assertIs<MarkdownBlock.OrderedList>(list.items[0].blocks[1])
        assertEquals(1, nested.start)
        assertEquals(text("child"), assertIs<MarkdownBlock.Paragraph>(nested.items.single().blocks.single()).children)
    }

    @Test fun blockQuote() = assertEquals(listOf(MarkdownBlock.BlockQuote(listOf(MarkdownBlock.Paragraph(text("quoted"))))), blocks("> quoted"))
    @Test fun multilineBlockQuote() = assertEquals(listOf(MarkdownBlock.BlockQuote(listOf(MarkdownBlock.Paragraph(text("one") + MarkdownInline.SoftLineBreak + text("two"))))), blocks("> one\n> two"))
    @Test fun fencedCode() = assertEquals(listOf(MarkdownBlock.CodeBlock("kotlin", "val x = 1\n\nprint(x)")), blocks("```kotlin\nval x = 1\n\nprint(x)\n```"))
    @Test fun indentedCode() = assertEquals(listOf(MarkdownBlock.CodeBlock(null, "one\n  two")), blocks("    one\n      two"))
    @Test fun horizontalRule() = assertEquals(listOf(MarkdownBlock.HorizontalRule), blocks("---"))

    @Test fun tableAlignmentAndInlineCells() {
        val table = assertIs<MarkdownBlock.Table>(blocks("| a | b | c | d |\n| :--- | :---: | ---: | --- |\n| **x** | y | z | w |").single())
        assertEquals(MarkdownColumnAlignment.entries, table.alignments.let { listOf(it[3], it[0], it[1], it[2]) })
        assertEquals(listOf("a", "b", "c", "d").map { MarkdownTableCell(text(it)) }, table.header.cells)
        assertEquals(listOf(MarkdownInline.Strong(text("x"))), table.rows.single().cells.first().children)
    }

    @Test fun codeSpanSpacing() {
        for ((source, code) in listOf("` x `" to "x", "`  x  `" to " x ", "`   `" to "   ", "`x\ny`" to "x y", "`` `x` ``" to "`x`")) {
            assertEquals(listOf(MarkdownInline.CodeSpan(code)), inline(source))
        }
    }

    @Test fun links() = assertEquals(listOf(MarkdownInline.Link("https://kinuvo.dev", listOf(MarkdownInline.Strong(text("Kinuvo"))))), inline("[**Kinuvo**](https://kinuvo.dev)"))
    @Test fun autolinks() {
        for ((source, label, destination) in listOf(Triple("<https://kinuvo.dev>", "https://kinuvo.dev", "https://kinuvo.dev"), Triple("https://kinuvo.dev", "https://kinuvo.dev", "https://kinuvo.dev"), Triple("<a@example.com>", "a@example.com", "mailto:a@example.com"))) {
            assertEquals(listOf(MarkdownInline.Link(destination, text(label))), inline(source))
        }
    }
    @Test fun image() = assertEquals(listOf(MarkdownInline.Image("alt", "https://kinuvo.dev/a.png")), inline("![alt](https://kinuvo.dev/a.png)"))
    @Test fun softLineBreak() = assertEquals(text("one") + MarkdownInline.SoftLineBreak + text("two"), inline("one\ntwo"))
    @Test fun hardLineBreak() {
        for (source in listOf("one  \ntwo", "one\\\ntwo")) assertEquals(text("one") + MarkdownInline.HardLineBreak + text("two"), inline(source))
    }
    @Test fun unclosedFormattingStaysText() = assertEquals(text("say **hi"), inline("say **hi"))
    @Test fun partialDocument() = assertEquals(listOf(MarkdownBlock.Paragraph(text("reply")), MarkdownBlock.CodeBlock("kt", "val x =")), blocks("reply\n\n```kt\nval x ="))
    @Test fun referenceLinks() = assertEquals(listOf(MarkdownInline.Link("/docs", text("docs"))), inline("[docs][id]\n\n[id]: /docs"))
    @Test fun escapedTextAndEntities() = assertEquals(text("* & © < &amp;"), inline("\\* &amp; &copy; &lt; &amp;amp;"))
    @Test fun everyStreamingPrefixParses() {
        val source = "# Reply\n\n- [x] **done**\n  - [next](/docs)\n\n```kt\nval x = `1`\n```"
        for (end in 0..source.length) markdownDocument(source.take(end))
    }
    @Test fun emptyDocument() = assertEquals(emptyList(), blocks(""))
}
