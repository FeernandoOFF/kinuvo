package kinuvo.ui.e2e

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import kinuvo.protocol.Api
import kotlin.test.Test

class ArtifactKeepAliveFlowTest {
    /** The last opened Artifact stays alive while another screen shows, so returning to it doesn't load it again. */
    @Test fun returningToTheLastArtifactKeepsItsPage() = appTest("artifact-keep-alive", desktop = true, script = {
        on("Make a report") {
            toolCall("artifact", """{"action":"create","title":"Weekly report","type":"md","content":"# Weekly report\n\nKept alive"}""")
            reply("Saved the weekly report.")
        }
    }) {
        pair()
        tap("Message")
        type("Make a report")
        tap("Send")
        await("Saved the weekly report.")
        tap(label("^Open$") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        tap("Expand artifact")
        await("Kept alive")
        settle()
        val loaded = server.spy.count(Api.Artifacts)
        tap("Artifacts")
        await("Pick an artifact to open it.")
        tap("Weekly report")
        await("Kept alive")
        screenshot("returned")
        // Only the list page asked for the Artifacts; the kept page didn't load again.
        requests(Api.Artifacts, loaded + 1)
    }
}
