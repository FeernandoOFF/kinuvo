package kinuvo.app

import kinuvo.app.artifacts.*
import kinuvo.protocol.artifact.ArtifactInfo
import kotlin.test.*

class ArtifactKindTest {
    private fun info(type: String) = ArtifactInfo("a", "A", type, "main", null, 0, 0)

    @Test fun editorTabsFollowTheType() {
        assertEquals(listOf(EditorTab.Visual, EditorTab.Source, EditorTab.Preview), info("html").editorTabs(wide = false, visual = true))
        assertEquals(listOf(EditorTab.Source), info("html").editorTabs(wide = true, visual = false))
        assertEquals(listOf(EditorTab.Source, EditorTab.Preview), info("svg").editorTabs(wide = false, visual = true))
        assertEquals(listOf(EditorTab.Source, EditorTab.Preview), info("md").editorTabs(wide = false, visual = true))
        assertEquals(listOf(EditorTab.Source), info("csv").editorTabs(wide = false, visual = true))
    }

    @Test fun onlySvgIsWrappedAsAPage() {
        assertEquals("<html><body style=\"margin:0\"><svg/></body></html>", info("svg").page("<svg/>"))
        assertEquals("<p>a</p>", info("html").page("<p>a</p>"))
    }
}
