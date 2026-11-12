package kinuvo.app.markdown

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser

class MarkdownHighlights(val text: String, val spans: List<MarkdownSpan>, private val tree: ASTNode) {
    fun activeTypes(start: Int, end: Int) = activeTypes(tree, start, end)
}

fun Flow<String>.markdownHighlights(): Flow<MarkdownHighlights> = distinctUntilChanged().conflate().map { text ->
    val tree = MarkdownParser(KinuvoMarkdown.flavour).buildMarkdownTreeFromString(text)
    MarkdownHighlights(text, markdownSpans(text, tree), tree)
}.flowOn(Dispatchers.Default)
