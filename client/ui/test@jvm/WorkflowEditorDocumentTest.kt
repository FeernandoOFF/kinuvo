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
        assertTrue(page.contains("kinuvo: 'workflow'"))
        assertFalse(Regex("<!--@").containsMatchIn(page))
        assertFalse(Regex("""(src|href)\s*=\s*["']?(https?:)?//""").containsMatchIn(page))
        assertFalse(Regex("""@import|url\(\s*["']?(https?:)?//""").containsMatchIn(page))
        // The only absolute URL is the SVG namespace Drawflow creates elements with.
        assertEquals(setOf("http://www.w3.org/2000/svg"), Regex("""https?://[^"'\s)]+""").findAll(page).map { it.value }.toSet())
    }

    @Test fun documentRunsThePageInASandboxedFrameWithNoNetworkAndTheArtifactBridge() = runBlocking {
        val document = workflowEditorDocument("abc123")
        assertTrue(document.contains("sandbox=\"allow-scripts\""))
        assertFalse(document.contains("allow-same-origin"))
        val policy = Regex("""Content-Security-Policy&quot; content=&quot;(.*?)&quot;""").find(document)!!.groupValues[1]
        assertTrue(policy.startsWith("default-src 'none';"))
        assertTrue("script-src 'nonce-abc123';" in policy)
        assertFalse("http" in policy)
        assertFalse(Regex("""(?<!\w)(unsafe-eval|connect-src [^;]*\S)""").containsMatchIn(policy))
        val scripts = Regex("""&lt;script.*?&gt;""").findAll(document).map { it.value }.toList()
        assertEquals(2, scripts.size)
        assertTrue(scripts.all { it == "&lt;script nonce=&quot;abc123&quot;&gt;" })
        assertTrue(document.contains("window.kinuvoEdit"))
        assertTrue(document.contains("window.kinuvoReceive"))
        assertTrue(document.contains("d.kinuvo===\"workflow\""))
    }

    @Test fun pagePostsEveryEventThroughTheBridgeAndOnlyTrustsItsParent() = runBlocking {
        val page = workflowEditorPage("n")
        assertTrue(page.contains("parent.postMessage({ kinuvo: 'workflow', json: JSON.stringify("))
        assertTrue(page.contains("e.source !== parent"))
        assertTrue(page.contains("['review', 'Check and edit the data']"))
        assertTrue(page.contains("['text', 'Fixed text or JSON the next steps read']"))
    }
}
