package kinuvo.harness.anytype

import kotlinx.serialization.json.*
import kotlin.test.*

class AnytypeBodyTest {
    @Test
    fun convertsLinksBothWays() {
        val text = """See <mention object_id="a1">Ana</mention> and [the office](anytype://object?objectId=o1&spaceId=s)"""
        assertEquals("See [[a1|Ana]] and [[o1|the office]]", AnytypeBody.canonical(text))
        assertEquals("""Met <mention object_id="a1">Ana</mention>""", AnytypeBody.native("Met [[a1|Ana]]"))
    }

    @Test
    fun replaceKeepsExistingLinksAsWritten() {
        val blocks = listOf(block("p1", "paragraph", "Visit [the office](anytype://object?objectId=o1) today"))
        val (block, text) = AnytypeBody.replace(blocks, "today", "tomorrow with [[a1|Ana]]")
        assertEquals("p1", block["id"]!!.jsonPrimitive.content)
        assertEquals("""Visit [the office](anytype://object?objectId=o1) tomorrow with <mention object_id="a1">Ana</mention>""", text)
    }

    @Test
    fun appendsInsideToggleHeadingsAndAfterSections() {
        val blocks = listOf(block("h", "toggle_heading_2", "**Ideas**"), block("t", "heading_1", "Top"), block("x", "paragraph", "Text"))
        assertEquals("inside" to "h", AnytypeBody.section(blocks, "ideas"))
        assertEquals("after" to "x", AnytypeBody.section(blocks, "Top"))
        assertNull(AnytypeBody.section(blocks, "Missing"))
    }

    @Test
    fun rendersTablesAndCode() {
        val table = buildJsonObject {
            put("id", "t"); put("type", "table")
            putJsonArray("rows") {
                add(buildJsonObject { putJsonArray("cells") { add("Key"); add("Action") } })
                add(buildJsonObject { putJsonArray("cells") { add("c"); add("Zoom") } })
            }
        }
        val code = buildJsonObject { put("id", "c"); put("type", "code"); put("language", "kotlin"); put("text", "println(1)") }
        assertEquals("| Key | Action |\n| --- | --- |\n| c | Zoom |\n\n```kotlin\nprintln(1)\n```", AnytypeBody.render(listOf(table, code)))
    }

    private fun block(id: String, type: String, text: String) = buildJsonObject { put("id", id); put("type", type); put("text", text) }
}
