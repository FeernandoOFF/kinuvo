package kinuvo.ui

import androidx.compose.ui.graphics.Color
import kinuvo.protocol.artifact.ArtifactFile
import kinuvo.protocol.artifact.ArtifactFiles
import kinuvo.ui.artifacts.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactOriginTest {
    private val files = mapOf(
        INDEX to SiteFile("text/html", "<p>home</p>".encodeToByteArray()),
        "app.js" to SiteFile("text/javascript", "run()".encodeToByteArray()),
        "img/a b.png" to SiteFile("image/png", byteArrayOf(1, 2, 3)),
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
        assertEquals(artifactPolicy(own = true), html["Content-Security-Policy"])
        assertTrue("default-src 'none'" in html.getValue("Content-Security-Policy"))
        assertTrue("frame-src" !in html.getValue("Content-Security-Policy"))
        assertTrue("script-src 'unsafe-inline' 'unsafe-eval' https: 'self' 'wasm-unsafe-eval';" in html.getValue("Content-Security-Policy"))
        assertTrue("connect-src 'self';" in html.getValue("Content-Security-Policy"))
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
        assertTrue(originPage("<p>x</p>", dark = false, deck = false).contains("content=\"${artifactPolicy(own = true)}\""))
        assertTrue(sandboxed("<p>x</p>").contains("content=\"${artifactPolicy()}\""))
    }

    @Test
    fun onlyAnOriginMayLoadAndFetchItsOwnFiles() {
        val own = artifactPolicy(own = true)
        val opaque = artifactPolicy()
        assertTrue("connect-src" !in opaque && "'self'" !in opaque && "wasm-unsafe-eval" !in opaque)
        for (directive in listOf("script-src", "style-src", "img-src", "font-src", "media-src", "connect-src")) {
            assertTrue(own.substringAfter("$directive ").substringBefore(';').contains("'self'"), directive)
        }
        assertTrue("connect-src 'self';" in own && "connect-src https" !in own && "default-src 'none'" in own)
        assertTrue("form-action 'none'" in own && "base-uri 'none'" in own && "frame-src" !in own)
        assertTrue("object-src" !in own)
    }

    @Test
    fun typesFollowTheExtension() {
        assertEquals("application/wasm", mimeOf("pkg/app.wasm"))
        assertEquals("text/javascript", mimeOf("a.MJS"))
        assertEquals("font/woff2", mimeOf("f.woff2"))
        assertEquals("application/octet-stream", mimeOf("data.bin"))
        assertEquals("application/vnd.sqlite3", mimeOf("app.sqlite"))
        assertEquals("application/octet-stream", mimeOf("tool.exe"))
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

    private val listing = ArtifactFiles(
        "app", "main.html",
        listOf(ArtifactFile("main.html", 5, "text/html", "h0"), ArtifactFile("wasm/m.wasm", 8, "application/wasm", "h1"), ArtifactFile("data/a b.json", 2, "application/json", "h2")),
    )

    @Test
    fun aFolderFetchesFilesWhenAPageFirstAsksAndKeepsThem() = runTest {
        val asked = mutableListOf<String>()
        val folder = ArtifactOrigin("https://app.invalid", ArtifactSite.folder("app", listing, "<p>entry</p>") { path -> asked += path; if (path == "data/a b.json") "{}".encodeToByteArray() else byteArrayOf(0, 0x61, 0x73, 0x6d) })
        assertEquals("https://app.invalid/main.html?v=${folder.site.version}", folder.url)
        assertEquals("<p>entry</p>", folder.serve("https://app.invalid/main.html").body.decodeToString())
        assertEquals("<p>entry</p>", folder.serve("https://app.invalid/").body.decodeToString())
        assertEquals(emptyList(), asked)

        val wasm = folder.serve("https://app.invalid/wasm/m.wasm?x=1")
        assertEquals(200, wasm.status)
        assertEquals("application/wasm", wasm.contentType)
        assertEquals(listOf<Byte>(0, 0x61, 0x73, 0x6d), wasm.body.toList())
        assertEquals("nosniff", wasm.headers["X-Content-Type-Options"])
        assertNull(wasm.headers["Content-Security-Policy"])
        folder.serve("https://app.invalid/wasm/m.wasm")
        assertEquals("{}", folder.serve("https://app.invalid/data/a%20b.json").body.decodeToString())
        assertEquals(listOf("wasm/m.wasm", "data/a b.json"), asked)

        assertEquals(404, folder.serve("https://app.invalid/unlisted.js").status)
        assertEquals(403, folder.serve("https://app.invalid/../wasm/m.wasm").status)
        assertEquals(403, folder.serve("https://other.invalid/wasm/m.wasm").status)
        assertEquals(404, folder.respond("https://app.invalid/data/a%20b.json").status)
        assertEquals(listOf("wasm/m.wasm", "data/a b.json"), asked)
    }

    @Test
    fun aFileTheServerCannotGiveIsABadGatewayAndAChangedHashIsFetchedAgain() = runTest {
        var reply: ByteArray? = null
        var calls = 0
        val folder = ArtifactOrigin("https://app.invalid", ArtifactSite.folder("app", listing, "<p/>") { calls++; reply })
        assertEquals(502, folder.serve("https://app.invalid/wasm/m.wasm").status)
        reply = byteArrayOf(1)
        assertEquals(listOf<Byte>(1), folder.serve("https://app.invalid/wasm/m.wasm").body.toList())
        reply = byteArrayOf(2)
        assertEquals(listOf<Byte>(1), folder.serve("https://app.invalid/wasm/m.wasm").body.toList())
        folder.site = ArtifactSite.folder("app", listing.copy(files = listing.files.map { if (it.path == "wasm/m.wasm") it.copy(hash = "h9") else it }), "<p/>") { calls++; reply }
        assertEquals(listOf<Byte>(2), folder.serve("https://app.invalid/wasm/m.wasm").body.toList())
        assertEquals(3, calls)
    }

    @Test
    fun aFoldersVersionTracksItsHashesAndPathsAreEncodedForUrls() {
        val a = ArtifactSite.folder("app", listing, "<p/>") { null }
        assertEquals(a.version, ArtifactSite.folder("app", listing, "<p/>") { null }.version)
        assertNotEquals(a.version, ArtifactSite.folder("app", listing.copy(files = listing.files.map { it.copy(hash = "x") }), "<p/>") { null }.version)
        assertEquals("a%20b/%C3%A9%3F%23.js", encodePath("a b/é?#.js"))
        assertEquals("img/a-b_c.v1~.png", encodePath("img/a-b_c.v1~.png"))
        assertEquals("a b/é?#.js", pathOf(encodePath("a b/é?#.js")))
    }
}
