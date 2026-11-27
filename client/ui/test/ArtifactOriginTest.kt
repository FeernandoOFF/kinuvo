package kinuvo.ui

import androidx.compose.ui.graphics.Color
import kinuvo.ui.artifacts.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactOriginTest {
    private val files = mapOf(
        INDEX to ArtifactFile("text/html", "<p>home</p>".encodeToByteArray()),
        "app.js" to ArtifactFile("text/javascript", "run()".encodeToByteArray()),
        "img/a b.png" to ArtifactFile("image/png", byteArrayOf(1, 2, 3)),
    )
    private val origin = ArtifactOrigin("https://notes-1a2b.invalid", ArtifactSite("notes-1a2b", files))

    @Test
    fun theOriginServesItsFilesWithTheirTypes() {
        val page = origin.respond("https://notes-1a2b.invalid/index.html?v=abc#top")
        assertEquals(200, page.status)
        assertEquals("<p>home</p>", page.body.decodeToString())
        assertEquals("text/html; charset=utf-8", page.contentType)
        assertEquals("text/javascript", origin.respond("https://notes-1a2b.invalid/app.js").mime)
        assertEquals(listOf<Byte>(1, 2, 3), origin.respond("https://notes-1a2b.invalid/img/a%20b.png").body.toList())
        assertEquals("image/png", origin.respond("https://notes-1a2b.invalid/img/a%20b.png").contentType)
        assertEquals("<p>home</p>", origin.respond("https://notes-1a2b.invalid/").body.decodeToString())
        assertEquals("https://notes-1a2b.invalid/index.html?v=${origin.site.version}", origin.url)
    }

    @Test
    fun anotherArtifactsAddressOrAPathOutsideTheSiteIsRefused() {
        for (url in listOf(
            "https://other-3c4d.invalid/index.html", "https://notes-1a2b.invalid.evil.com/index.html", "https://notes-1a2b.invalid@evil.com/index.html",
            "http://notes-1a2b.invalid/index.html", "kinuvo-artifact://notes-1a2b/index.html", "https://notes-1a2b.invalid:8443/index.html",
        )) assertEquals(403, origin.respond(url).status, url)
        for (path in listOf("../index.html", "img/../../x", "%2e%2e/index.html", "%2E%2E%2Findex.html", "img%2f..%2f..%2fx", "a\\b", "img//a", "%00", "bad%zz", "app.js/", "./app.js")) {
            assertEquals(403, origin.respond("https://notes-1a2b.invalid/$path").status, path)
        }
        assertEquals(404, origin.respond("https://notes-1a2b.invalid/missing.js").status)
        assertEquals(0, origin.respond("https://notes-1a2b.invalid/missing.js").body.size)
    }

    @Test
    fun pathsAreNormalisedOnceAndOnlyWithinTheSite() {
        assertEquals(INDEX, pathOf(""))
        assertEquals(INDEX, pathOf("?v=1"))
        assertEquals("a/b.js", pathOf("a/b.js#x"))
        assertEquals("a b.js", pathOf("a%20b.js"))
        assertEquals("é.js", pathOf("%C3%A9.js"))
        assertNull(pathOf("a/../b"))
        assertNull(pathOf("%2e/a"))
        assertNull(pathOf("a/%5cb"))
    }

    @Test
    fun documentsCarryThePolicyAsAHeaderAndTheOthersDoNot() {
        val html = artifactHeaders("text/html")
        assertEquals(artifactPolicy(), html["Content-Security-Policy"])
        assertTrue("default-src 'none'" in html.getValue("Content-Security-Policy"))
        assertTrue("connect-src" !in html.getValue("Content-Security-Policy") && "frame-src" !in html.getValue("Content-Security-Policy"))
        assertTrue("script-src 'unsafe-inline' 'unsafe-eval' https:;" in html.getValue("Content-Security-Policy"))
        for (type in listOf("text/javascript", "application/wasm", "image/png")) assertNull(artifactHeaders(type)["Content-Security-Policy"])
        for (headers in listOf(html, artifactHeaders("image/png"))) {
            assertEquals("nosniff", headers["X-Content-Type-Options"])
            assertEquals("no-referrer", headers["Referrer-Policy"])
            assertEquals("no-store", headers["Cache-Control"])
        }
        assertEquals(html, origin.respond("https://notes-1a2b.invalid/index.html").headers)
        assertEquals(artifactPolicy("'nonce-a'", "", "'self'"), "default-src 'none'; script-src 'nonce-a'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; frame-src 'self'; form-action 'none'; base-uri 'none'")
    }

    @Test
    fun theMetaPolicyAndTheHeaderPolicyAreOne() {
        assertTrue(originPage("<p>x</p>", dark = false, deck = false).contains("content=\"${artifactPolicy()}\""))
        assertTrue(sandboxed("<p>x</p>").contains("content=\"${artifactPolicy()}\""))
    }

    @Test
    fun typesFollowTheExtension() {
        assertEquals("application/wasm", mimeOf("pkg/app.wasm"))
        assertEquals("text/javascript", mimeOf("a.MJS"))
        assertEquals("font/woff2", mimeOf("f.woff2"))
        assertEquals("application/octet-stream", mimeOf("data.bin"))
        assertEquals("application/octet-stream", mimeOf("noextension"))
    }

    @Test
    fun onlyDnsLabelsGetAnOrigin() {
        for (id in listOf("notes-1a2b", "a", "q3-report-0f9e")) assertTrue(originId(id), id)
        for (id in listOf("", "-a", "a-", "Notes", "a.b", "a_b", "a b", "x".repeat(64), "é")) assertTrue(!originId(id), id)
        assertTrue(originId("x".repeat(63)))
    }

    @Test
    fun aVersionTracksTheBytesServed() {
        val a = ArtifactSite.page("a", "<p>1</p>")
        assertEquals(a.version, ArtifactSite.page("a", "<p>1</p>").version)
        assertNotEquals(a.version, ArtifactSite.page("a", "<p>2</p>").version)
        assertNotEquals(a.version, ArtifactSite("a", files).version)
    }

    @Test
    fun theFrameLoadsTheOriginAndKeepsItsPeersOut() {
        val page = originDocument("https://notes-1a2b.invalid/index.html?v=1&x=2", Color.White, deck = true)
        assertTrue(page.contains("sandbox=\"allow-scripts allow-same-origin\""))
        assertTrue(page.contains("src=\"https://notes-1a2b.invalid/index.html?v=1&amp;x=2\""))
        assertTrue(!page.contains("srcdoc") && !page.contains("allow-popups") && !page.contains("allow-top-navigation"))
        assertTrue(page.contains("window.kinuvoReceive=function(m)") && page.contains("d.kinuvo===\"deck\"?JSON.stringify(d)"))
        assertTrue(page.contains("pointer-events:none"))
        assertTrue(!originDocument("https://a.invalid/index.html").contains("kinuvoReceive"))
    }

    @Test
    fun theServedPageKeepsItsShimsExceptTheStorageOneTheOriginReplaces() {
        val page = originPage("<script>localStorage.x=1</script>", dark = true, deck = true)
        assertTrue(page.startsWith("<!doctype html><html><head><meta data-kinuvo-editor charset=\"utf-8\"><meta data-kinuvo-editor http-equiv=\"Content-Security-Policy\""))
        assertTrue(!page.contains("['localStorage','sessionStorage']"))
        assertTrue(page.contains("['pushState','replaceState']"))
        assertTrue(page.contains("if(parent===window)return;") && page.contains("Reveal.getIndices"))
        assertTrue(page.contains("document.documentElement.dataset.theme=\"dark\""))
        assertTrue(page.endsWith("</head><script>localStorage.x=1</script>"))
        assertTrue(!originPage("<p>x</p>", dark = false, deck = false).contains("Reveal.getIndices"))
        assertTrue(sandboxed("<p>x</p>").contains("['localStorage','sessionStorage']"))
    }
}
