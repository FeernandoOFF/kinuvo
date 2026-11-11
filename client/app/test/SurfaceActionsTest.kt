package kinuvo.app

import dev.ynagai.a2ui.core.protocol.ActionMessage
import kinuvo.app.session.ChatItem
import kinuvo.app.session.SessionData
import kinuvo.app.surface.SurfaceDrafts
import kinuvo.app.surface.SurfaceSubmissions
import kinuvo.app.surface.dateTimeValue
import kinuvo.app.surface.openSurfaceUrl
import kinuvo.app.surface.surfaceAction
import kinuvo.app.surface.surfaceDataModels
import kinuvo.protocol.session.SessionEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class SurfaceActionsTest {
    private val context = JsonObject(mapOf("name" to JsonPrimitive("Alex")))
    private val model = JsonObject(mapOf("name" to JsonPrimitive("Alex"), "remind" to JsonPrimitive(true)))

    @Test fun buildsActionWithResolvedContextAndFullModel() {
        val message = ActionMessage(name = "save", surfaceId = "form", sourceComponentId = "submit", timestamp = "2026-11-06T00:00:00Z", context = context, userMessage = "Save plan")
        val action = message.surfaceAction("call", model)
        assertEquals("call", action.callId)
        assertEquals("form", action.surfaceId)
        assertEquals("submit", action.sourceComponentId)
        assertEquals("save", action.name)
        assertEquals(context, action.context)
        assertEquals(model, action.dataModel)
    }

    @Test fun latestModelsAreScopedByCallAndSurfaceAfterSessionReload() {
        val action = ActionMessage(name = "save", surfaceId = "form", sourceComponentId = "submit", timestamp = "now", context = context).surfaceAction("call", model)
        val old = JsonObject(mapOf("name" to JsonPrimitive("Old")))
        val entries = listOf(
            SessionEntry.UserMessage("old", action = action.copy(dataModel = old)),
            SessionEntry.UserMessage("other surface", action = action.copy(surfaceId = "other", dataModel = old)),
            SessionEntry.UserMessage("latest", action = action),
            SessionEntry.UserMessage("typed"),
            SessionEntry.UserMessage("unrelated", action = action.copy(callId = "other", dataModel = old)),
        )
        val items = SessionData("session", entries = entries).items
        assertEquals(action, (items[2] as ChatItem.User).action)
        assertEquals(mapOf("form" to model, "other" to old), items.surfaceDataModels("call"))
        assertEquals(emptyMap(), items.surfaceDataModels("missing"))
    }

    @Test fun invalidDateDraftBlocksSubmissionUntilCorrected() {
        val drafts = SurfaceDrafts()
        var submitted = 0
        fun edit(text: String) = drafts.validate("date", dateTimeValue(text, true, false) != null)
        fun submit() { if (drafts.canSubmit) submitted++ }
        edit("2026-02-28")
        edit("2026-02-30")
        submit()
        assertEquals(0, submitted)
        edit("2026-03-01")
        submit()
        assertEquals(1, submitted)
    }

    @Test fun newerSubmissionOverridesLocalEditsEvenWithSameModel() {
        val submissions = SurfaceSubmissions()
        var current = model
        val apply: (String, JsonObject) -> Unit = { _, next -> current = next }
        submissions.update(mapOf("form" to ("first" to model)), apply)
        current = context
        submissions.update(mapOf("form" to ("first" to model)), apply)
        assertEquals(context, current)
        submissions.update(mapOf("form" to ("second" to model)), apply)
        assertEquals(model, current)
    }

    @Test fun urlOpenerAcceptsOnlyHttpSchemes() {
        val opened = mutableListOf<String>()
        listOf("https://example.com", "http://example.com", "file:///secret", "javascript:alert(1)", "mailto:a@example.com").forEach {
            openSurfaceUrl(it, opened::add)
        }
        assertEquals(listOf("https://example.com", "http://example.com"), opened)
    }
}
