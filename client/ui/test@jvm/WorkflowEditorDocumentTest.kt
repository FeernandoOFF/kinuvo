package kinuvo.ui

import kinuvo.ui.workflow.workflowEditorDocument
import kinuvo.ui.workflow.workflowEditorPage
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WorkflowEditorDocumentTest {
    @Test fun pageInlinesDrawflowAndTheEditorWithoutRemoteUrls() = runBlocking {
        val page = workflowEditorPage()
        assertTrue(page.contains("Drawflow"))
        assertTrue(page.contains(".drawflow .drawflow-node"))
        assertTrue(page.contains("jsonrpc"))
        assertFalse(Regex("<!--@").containsMatchIn(page))
        assertFalse(Regex("""(src|href)\s*=\s*["']?(https?:)?//""").containsMatchIn(page))
        assertFalse(Regex("""@import|url\(\s*["']?(https?:)?//""").containsMatchIn(page))
        // The only absolute URL is the SVG namespace Drawflow creates elements with.
        assertEquals(setOf("http://www.w3.org/2000/svg"), Regex("""https?://[^"'\s)]+""").findAll(page).map { it.value }.toSet())
    }

    @Test fun documentRunsThePageInTheSandboxedHtmlSurfaceFrameWithNoNetwork() = runBlocking {
        val document = workflowEditorDocument()
        assertTrue(document.contains("sandbox=\"allow-scripts\""))
        assertFalse(document.contains("allow-same-origin"))
        assertTrue(document.contains("connect-src 'none'"))
        assertTrue(document.contains("kinuvoNative"))
        assertFalse(document.contains("</script><script>"))
    }
}
