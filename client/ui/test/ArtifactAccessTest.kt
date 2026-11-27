package kinuvo.ui

import kinuvo.protocol.artifact.ArtifactCapabilities
import kinuvo.ui.artifacts.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArtifactAccessTest {
    private val api = ArtifactAccess(listOf("https://api.example.com", "wss://api.example.com", "wss://*.rt.example.com:8443"))
    private val origin = ArtifactOrigin("https://notes-1a2b.invalid", ArtifactSite.page("notes-1a2b", "<p>x</p>"))

    private fun directive(policy: String, name: String) = policy.substringAfter("$name ", "").substringBefore(';')

    @Test
    fun grantedOriginsReachConnectSrcAndNothingElseWidens() {
        val none = artifactPolicy(own = true)
        val policy = artifactPolicy(own = true, access = api)
        assertEquals("'self' https://api.example.com wss://api.example.com wss://*.rt.example.com:8443", directive(policy, "connect-src"))
        for (name in listOf("script-src", "style-src", "img-src", "font-src")) assertEquals(directive(none, name), directive(policy, name), name)
        assertEquals(directive(none, "media-src") + " https://api.example.com", directive(policy, "media-src"))
        assertTrue("default-src 'none'" in policy && "form-action 'none'" in policy && "frame-src" !in policy)
    }

    @Test
    fun withoutAnyGrantThePolicyIsUnchanged() {
        assertEquals(artifactPolicy(own = true), artifactPolicy(own = true, access = ArtifactAccess.None))
        assertEquals("'self'", directive(artifactPolicy(own = true), "connect-src"))
        assertTrue("connect-src" !in artifactPolicy())
    }

    @Test
    fun anOpaquePageGetsOnlyTheGrantedOriginsToConnectTo() {
        assertEquals("https://api.example.com wss://api.example.com wss://*.rt.example.com:8443", directive(artifactPolicy(access = api), "connect-src"))
        assertTrue(artifactDocument("<p>x</p>", access = api).contains("connect-src https://api.example.com"))
        assertTrue("connect-src" !in artifactDocument("<p>x</p>"))
    }

    @Test
    fun theHeaderAndTheMetaPolicyAgreeForTheGrants() {
        assertEquals(artifactPolicy(own = true, access = api), artifactHeaders("text/html", api)["Content-Security-Policy"])
        assertTrue(originPage("<p>x</p>", dark = false, deck = false, access = api).contains("content=\"${artifactPolicy(own = true, access = api)}\""))
        assertEquals(artifactPolicy(own = true, access = api), ArtifactOrigin("https://a.invalid", ArtifactSite.page("a", "<p/>", api)).respond("https://a.invalid/index.html").headers["Content-Security-Policy"])
        assertEquals(artifactPolicy(own = true), origin.respond("https://notes-1a2b.invalid/index.html").headers["Content-Security-Policy"])
    }

    @Test
    fun grantsAreCheckedAgainBeforeTheyReachAPolicy() {
        val hostile = ArtifactCapabilities(network = listOf("https://ok.example.com", "https://x.com; script-src *", "http://plain.example.com", "https://a.com https://b.com", "*"), popups = true)
        assertEquals(listOf("https://ok.example.com"), ArtifactAccess.of(hostile).network)
        assertEquals(ArtifactAccess.None, ArtifactAccess.of(null))
        assertEquals(popupsSupported, ArtifactAccess.of(hostile).popups)
    }

    @Test
    fun aWindowThePageMayOpenMayEmbedItsOwnPagesAndNoOthers() {
        val popups = ArtifactAccess(popups = true)
        assertTrue("frame-src 'self';" in artifactPolicy(own = true, access = popups))
        assertTrue("frame-src" !in artifactPolicy(own = true))
        assertTrue("frame-src" !in artifactPolicy(access = popups))
        assertEquals("'self' file: blob: data:", directive(artifactPolicy(frames = "'self' file: blob: data:", own = true, access = popups), "frame-src"))
    }

    @Test
    fun theFrameOpensWindowsOnlyWhenGranted() {
        assertTrue(!originDocument("https://a.invalid/index.html").contains("allow-popups"))
        assertTrue(originDocument("https://a.invalid/index.html", popups = true).contains("sandbox=\"allow-scripts allow-same-origin allow-popups\""))
        assertTrue(!artifactDocument("<p/>", access = ArtifactAccess(popups = true)).contains("allow-popups"))
    }

    @Test
    fun aChangedGrantReloadsThePage() {
        val plain = ArtifactSite.page("a", "<p/>")
        assertNotEquals(plain.version, ArtifactSite.page("a", "<p/>", api).version)
        assertNotEquals(ArtifactSite.page("a", "<p/>", api).version, ArtifactSite.page("a", "<p/>", ArtifactAccess(api.network, popups = true)).version)
        assertEquals(ArtifactSite.page("a", "<p/>", api).version, ArtifactSite.page("a", "<p/>", api.copy()).version)
    }

    @Test
    fun anOwnPageOrABlankWindowOpensInTheAppAndLinksGoToTheBrowser() {
        assertEquals(PopupAction.Window, popupAction(null, origin))
        assertEquals(PopupAction.Window, popupAction("", origin))
        assertEquals(PopupAction.Window, popupAction("about:blank", origin))
        assertEquals(PopupAction.Window, popupAction("https://notes-1a2b.invalid/index.html?receiver&v=1", origin))
        assertEquals(PopupAction.Window, popupAction("https://notes-1a2b.invalid/", origin))
        assertEquals(PopupAction.Browser, popupAction("https://example.com/docs", origin))
        assertEquals(PopupAction.Browser, popupAction("http://example.com", origin))
        assertEquals(PopupAction.Browser, popupAction("mailto:me@example.com", origin))
        assertEquals(PopupAction.Browser, popupAction("https://notes-1a2b.invalid.evil.com/index.html", origin))
        assertEquals(PopupAction.Browser, popupAction("https://other-3c4d.invalid/index.html", origin))
    }

    @Test
    fun everythingElseIsDropped() {
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "data:text/html,<p>x</p>", "blob:https://a.invalid/1", "intent://x#Intent;end", "kinuvo-artifact://other/index.html", "tel:123", "notaurl")) {
            assertEquals(PopupAction.Block, popupAction(url, origin), url)
        }
        assertFalse(popupAction("https://example.com", null) == PopupAction.Window)
    }
}
