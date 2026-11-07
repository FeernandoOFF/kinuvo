package kinuvo.ui

import kinuvo.ui.artifacts.*
import androidx.compose.ui.graphics.Color
import kinuvo.protocol.Artboard
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private val sample = """{"artboards":[{"id":"home","name":"Home </script><b>","width":390,"height":844,"html":"<h1>Hi</h1><script>steal()</script>"},{"id":"b","name":"B","width":800,"height":600,"html":"<p>\"x\"</p>"},{"id":"c","name":"C","width":320,"height":480,"html":"<p>c</p>"}]}"""

    @Test
    fun designCanvasRunsOnlyItsOwnScriptAndKeepsArtboardsReadOnly() {
        val page = designDocument(sample, nonce = "abc", background = Color(0xFF101010))
        val inner = frame(page)
        assertTrue(inner.contains("script-src 'nonce-abc'"))
        assertTrue(!inner.contains("'unsafe-inline' 'unsafe-eval'"))
        assertTrue(inner.contains("<script nonce=\"abc\">"))
        assertTrue(inner.contains("setAttribute(\"sandbox\",\"allow-same-origin\")"))
        assertTrue(!inner.contains("allow-scripts"))
        assertTrue(page.contains("sandbox=\"allow-scripts allow-same-origin\""))
        assertTrue(page.contains("e.source!==f.contentWindow"))
        assertTrue(inner.contains("background:rgb(16,16,16)"))
        assertTrue(inner.contains("pointer-events:none"))
        assertTrue(page.contains("maximum-scale=1,user-scalable=no"))
        assertTrue(!frame(artifactDocument("<p/>")).contains("user-scalable"))
        assertTrue(artifactDocument("<p/>").contains("content=\"width=device-width,initial-scale=1\""))
    }

    @Test
    fun theBridgeForwardsOnlyDesignEnvelopesFromItsFrame() {
        val page = designDocument(sample, nonce = "abc")
        assertTrue(page.contains("d.kinuvo===\"design-edit\"&&typeof d.json===\"string\""))
        assertTrue(page.contains("d.kinuvo===\"design-open\"&&typeof d.id===\"string\""))
        assertEquals(DesignMessage.Open("a"), designMessage("""{"kinuvo":"design-open","id":"a"}"""))
        assertEquals(DesignMessage.Edit("{}"), designMessage("""{"kinuvo":"design-edit","json":"{}"}"""))
        assertEquals(null, designMessage("<p>html</p>"))
        assertEquals(null, designMessage("""{"kinuvo":"other"}"""))
    }

    @Test
    fun editorStylesAreMarkedSoSavingStripsThem() {
        val inner = frame(designDocument(sample, nonce = "abc"))
        assertTrue(inner.contains("setAttribute(\"data-kinuvo-editor\",\"\")"))
        assertTrue(inner.contains("querySelectorAll(\"[data-kinuvo-editor]\")"))
        assertTrue(inner.contains("removeAttribute(\"contenteditable\")"))
        assertTrue(inner.contains("kinuvo:\"design-edit\""))
    }

    @Test
    fun designJsonCannotCloseTheCanvasScript() {
        val inner = frame(designDocument(sample, nonce = "abc"))
        val script = inner.substringAfter("<script nonce=\"abc\">")
        assertTrue(script.indexOf("</script>") == script.length - "</script></body>".length)
        assertTrue(script.contains("Home \\u003c/script>\\u003cb>"))
    }

    @Test
    fun anArtboardIsShownAtItsSizeInTheNormalSandbox() {
        val page = artboardDocument(Artboard("b", "B", 800, 600, html = "<p>x</p>"), Color.White)
        assertTrue(page.contains("width:800px;height:600px"))
        assertTrue(page.contains("sandbox=\"allow-scripts\""))
        assertTrue(!page.contains("allow-same-origin"))
        assertTrue(page.contains("Content-Security-Policy"))
    }

    @Test
    fun focusAndFillAreBakedIntoTheCanvas() {
        val inner = frame(designDocument(sample, nonce = "abc", focus = "b", fill = true))
        assertTrue(inner.contains("F=\"b\",FL=true;"))
        assertTrue(frame(designDocument(sample, nonce = "abc")).contains("F=null,FL=false;"))
        assertTrue(inner.contains("script-src 'nonce-abc'"))
        assertTrue(frame(designDocument(sample, focus = "x\"</script>")).contains("F=\"x\\\"\\u003c/script>\""))
    }
}
