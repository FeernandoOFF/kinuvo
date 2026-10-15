package kinuvo.ui

import kinuvo.ui.artifacts.*
import kotlin.test.Test
import kotlin.test.assertTrue

class ArtifactTest {
    @Test
    fun activeContentCannotEscapeTheOpaqueSandbox() {
        val html = "\"></iframe><script>steal()</script>"
        val page = artifactDocument(html)
        assertTrue(page.contains("sandbox=\"allow-scripts\""))
        assertTrue(!page.contains("allow-same-origin"))
        assertTrue(!page.contains("allow-popups"))
        assertTrue(!page.contains(html))
        assertTrue(page.contains("&lt;script&gt;steal()&lt;/script&gt;"))
    }

    @Test
    fun policyComesBeforeAnythingTheArtifactWrites() {
        val page = sandboxed("<!-- <head> --><html><head data-x='>'><title>x</title></head></html>")
        assertTrue(page.startsWith("<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\""))
        assertTrue(page.endsWith("</head><!-- <head> --><html><head data-x='>'><title>x</title></head></html>"))
    }
}
