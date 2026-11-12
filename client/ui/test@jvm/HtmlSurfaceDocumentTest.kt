package kinuvo.ui

import kinuvo.ui.surface.HtmlPolicy
import kinuvo.ui.surface.htmlSurfaceDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class HtmlSurfaceDocumentTest {
    @Test fun isolatesContentAndEscapesScriptTerminators() {
        val document = htmlSurfaceDocument("</script><script>parent.pwned=true</script>", false)
        assertTrue(document.contains("sandbox=\"allow-scripts\""))
        assertFalse(document.contains("allow-same-origin"))
        assertFalse(document.contains("</script><script>parent.pwned"))
        assertTrue(document.contains("connect-src 'none'"))
        assertTrue(document.contains("event.source!==app.contentWindow"))
        assertTrue(document.contains("ResizeObserver"))
        assertTrue(document.indexOf("addEventListener('message'") < document.indexOf("app.srcdoc="))
        assertTrue(document.contains("overflow:hidden"))
        assertTrue(htmlSurfaceDocument("<p>Hi</p>", true).contains("overflow:auto"))
    }

    @Test fun mcpAppsWidenThePolicyOnlyToDeclaredHttpsOrigins() {
        val policy = HtmlPolicy.of(Json.parseToJsonElement("""{"csp":{"connectDomains":["https://api.example.com","http://plain.example.com","https://x.com/path","javascript:alert(1)"],
            "resourceDomains":["https://*.cdn.example.com/"]}}""").jsonObject)
        assertEquals(HtmlPolicy(listOf("https://api.example.com"), listOf("https://*.cdn.example.com")), policy)
        val document = htmlSurfaceDocument("<p>Hi</p>", false, policy)
        assertTrue(document.contains("connect-src https://api.example.com;"))
        assertTrue(document.contains("script-src 'unsafe-inline' https://*.cdn.example.com;"))
        assertFalse(document.contains("plain.example.com"))
    }
}
