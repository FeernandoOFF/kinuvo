package kinuvo.protocol

import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class ReflectionReviewNoteTest {
    @Test
    fun oldReviewsKeepDefaultsAndNotedReviewsRoundTripWithPendingDrafts() {
        val old = ProtocolJson.decodeFromString(SessionEntry.serializer(), """{"type":"reflection_review","proposalId":"p","approved":false}""")
        assertEquals(SessionEntry.ReflectionReview("p", false), old)
        val proposal = ReflectionProposal("new", "conventions", JsonObject(emptyMap()), "Organization", "Review note", conventionsBefore = "Before")
        val entry: SessionEntry = SessionEntry.ReflectionReview("p", true, "Correction", ReflectionResult(proposals = listOf(proposal)))
        assertEquals(entry, ProtocolJson.decodeFromString(SessionEntry.serializer(), ProtocolJson.encodeToString(SessionEntry.serializer(), entry)))
        val params = ReviewReflectionParams("agent", "session", "p", true, "Correction")
        assertEquals(params, ProtocolJson.decodeFromString(ReviewReflectionParams.serializer(), ProtocolJson.encodeToString(ReviewReflectionParams.serializer(), params)))
    }
}
