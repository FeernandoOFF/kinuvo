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
        assertTrue(page.startsWith("<!doctype html><html><head><meta data-kinuvo-editor charset=\"utf-8\"><meta data-kinuvo-editor http-equiv=\"Content-Security-Policy\""))
        assertTrue(page.endsWith("</head><!-- <head> --><html><head data-x='>'><title>x</title></head></html>"))
    }

    @Test
    fun aSavedEditorPolicyIsDropped() {
        val leaked = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'nonce-0123456789abcdef0123456789abcdef'; style-src https:\">"
        assertTrue(!sandboxed("<head>$leaked</head><p>x</p>").contains("0123456789abcdef"))
    }

    private fun frame(page: String) = page.substringAfter("srcdoc=\"").substringBefore("\"></iframe>")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    @Test
    fun visualEditingBlocksTheArtifactsOwnScriptsAndKeepsItsDoctype() {
        val page = visualDocument("<!DOCTYPE html PUBLIC \"x\"><body onclick=\"steal()\"><script>steal()</script></body>", nonce = "abc")
        val inner = frame(page)
        assertTrue(inner.contains("script-src 'nonce-abc'"))
        assertTrue(!inner.contains("'unsafe-inline' 'unsafe-eval'"))
        assertTrue(inner.contains("<script data-kinuvo-editor nonce=\"abc\">"))
        assertTrue(inner.contains("DT=\"\\u003c!DOCTYPE html PUBLIC \\\"x\\\">\""))
        assertTrue(page.contains("sandbox=\"allow-scripts\""))
        assertTrue(!page.contains("allow-same-origin"))
        assertTrue(page.contains("e.source!==f.contentWindow"))
    }

    @Test
    fun theDoctypeIsDefaultedAndCannotCloseTheEditorScript() {
        assertTrue(frame(visualDocument("<p>x</p>", "n")).contains("DT=\"\\u003c!doctype html>\""))
        val script = frame(visualDocument("<!doctype html></script><p>", "n")).substringAfter("data-kinuvo-editor nonce=\"n\">")
        assertTrue(script.indexOf("</script>") == script.length - "</script>".length)
    }
}
