package kinuvo.app.artifacts

import kinuvo.protocol.ArtifactInfo

enum class EditorTab { Visual, Source, Preview }

/** Rendered in a web view. */
val ArtifactInfo.web get() = type == "html" || type == "svg"

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
