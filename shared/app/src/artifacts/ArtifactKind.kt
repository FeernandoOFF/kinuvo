package kinuvo.app.artifacts

import kinuvo.protocol.ArtifactInfo
import kinuvo.protocol.Design
import kotlinx.serialization.json.Json

enum class EditorTab { Visual, Source, Preview }

/** Rendered in a web view. */
val ArtifactInfo.web get() = type == "html" || type == "svg"

/** Artboards on a canvas; edited as JSON source. */
val ArtifactInfo.isDesign get() = type == "design"

/** Has a rendered view beside its source: web pages and Markdown. */
val ArtifactInfo.previewable get() = web || type == "md"

/** What an editor offers: [visual] editing is html only; narrow editors show the preview as its own tab. */
fun ArtifactInfo.editorTabs(wide: Boolean, visual: Boolean) = buildList {
    if (visual && type == "html") add(EditorTab.Visual)
    add(EditorTab.Source)
    if (previewable && !wide) add(EditorTab.Preview)
}

/** [content] as a web page; an SVG is wrapped in one. */
fun ArtifactInfo.page(content: String) = if (type == "svg") "<html><body style=\"margin:0\">$content</body></html>" else content

private val designJson = Json { ignoreUnknownKeys = true }

/** [content] as a [Design], or null when it doesn't decode (a draft being typed). */
fun designOf(content: String): Design? = runCatching { designJson.decodeFromString<Design>(content) }.getOrNull()
