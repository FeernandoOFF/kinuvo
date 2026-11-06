package kinuvo.app

import kinuvo.app.surface.*
import dev.ynagai.a2ui.core.surface.EvaluationScope
import dev.ynagai.a2ui.core.surface.JsonPointer
import dev.ynagai.a2ui.core.surface.write
import kotlinx.serialization.json.*
import kotlin.test.*

class SurfaceCollectionsTest {
    private val json = Json

    @Test fun collections() {
        val rows = json.parseToJsonElement("""[{"status":["Done"]},{"status":["Todo"]}]""").jsonArray
        assertEquals(3, appendSurfaceValue(rows, JsonObject(emptyMap())).size)
        assertEquals(rows[1], removeSurfaceValue(rows, 0).single())
        assertEquals(rows, removeSurfaceValue(rows, -1))
        assertEquals(1, countSurfaceValues(rows, buildJsonObject { put("status", "Done") }))
        assertEquals(2, countSurfaceValues(rows))
    }

    @Test fun nestedTotals() {
        val days = json.parseToJsonElement("""{"days":[{"tasks":[{"minutes":60},{"minutes":15}]},{"tasks":[{"minutes":240}]}]}""")
        assertEquals(315.0, sumSurfaceValues(surfaceValues(days, listOf("days", "*", "tasks", "*", "minutes"))))
        assertEquals("5h 15m", formatSurfaceDuration(315.0))
        assertEquals("1h 00m", formatSurfaceDuration(60.0))
        assertEquals("0h 00m", formatSurfaceDuration(-1.0))
    }

    @Test fun nestedTemplateEdits() {
        val model = json.parseToJsonElement("""{"days":[{"tasks":[{"minutes":60}]},{"tasks":[{"minutes":15},{"minutes":30}]}]}""").jsonObject
        val day = EvaluationScope.Collection(EvaluationScope.Root, JsonPointer.parse("/days"), 1)
        val item = EvaluationScope.Collection(day, JsonPointer.parse("tasks"), 0)
        val edit = assertNotNull(SurfaceFunctions(model, item).edit("removeAt", emptyMap()))
        val changed = model.write(edit.first, edit.second)
        assertEquals(90.0, sumSurfaceValues(surfaceValues(changed, listOf("days", "*", "tasks", "*", "minutes"))))
        val append = assertNotNull(SurfaceFunctions(changed, day).edit("append", mapOf("path" to JsonPrimitive("tasks"), "value" to buildJsonObject { put("minutes", 45) })))
        assertEquals("/days/1/tasks", append.first.toString())
        assertEquals(135.0, sumSurfaceValues(surfaceValues(changed.write(append.first, append.second), listOf("days", "*", "tasks", "*", "minutes"))))
    }

    @Test fun evaluatesNestedFunctionsAndCopy() {
        val model = json.parseToJsonElement("""{"rows":[{"status":["Done"]},{"status":["Todo"]}]}""").jsonObject
        val functions = SurfaceFunctions(model, EvaluationScope.Root)
        fun evaluate(source: String) = functions.evaluate(json.parseToJsonElement(source))
        assertEquals(JsonPrimitive("1"), evaluate("""{"call":"formatNumber","args":{"value":{"call":"count","args":{"values":{"path":"/rows"},"where":{"status":"Done"}}}}}"""))
        assertEquals(JsonPrimitive("1h 15m"), evaluate("""{"call":"formatDuration","args":{"minutes":{"call":"sum","args":{"values":[60,[15]]}}}}"""))
        assertEquals("| status |\n| --- |\n| Done |\n| Todo |", surfaceMarkdownTable(model.getValue("rows")))
        assertEquals("| name |\n| --- |\n| a\\|b<br>c |", surfaceMarkdownTable(json.parseToJsonElement("""[{"name":"a|b\nc"}]""")))
        assertNull(functions.edit("removeAt", mapOf("path" to JsonPrimitive("/rows"))))
        assertNull(functions.edit("removeAt", mapOf("path" to JsonPrimitive("/rows"), "index" to JsonPrimitive("invalid"))))
    }

    @Test fun stepperClamps() {
        assertEquals(0.0, stepperValue(10.0, -15.0))
        assertEquals(60.0, stepperValue(50.0, 15.0, max = 60.0))
        assertEquals(30.0, stepperValue(15.0, 15.0))
        assertEquals(0.0, stepperValue(Double.NaN, Double.NaN))
    }
}
