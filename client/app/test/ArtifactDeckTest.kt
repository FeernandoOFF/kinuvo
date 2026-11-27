package kinuvo.app

import kinuvo.app.artifacts.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class ArtifactDeckTest {
    private val report = """{"kinuvo":"deck","h":2,"v":1,"f":0,"n":5,"total":12,"notes":"  Say hello\nthen pause ","next":{"title":"Demo","text":"Live"},"ready":true,"changed":false}"""

    @Test fun parsesTheAdaptersReport() {
        val parsed = assertNotNull(deckReport(report))
        assertEquals(DeckPosition(2, 1, 0), parsed.status.position)
        assertEquals(5, parsed.status.number)
        assertEquals(12, parsed.status.total)
        assertEquals("Say hello\nthen pause", parsed.status.notes)
        assertEquals(DeckSlide("Demo", "Live"), parsed.status.next)
        assertTrue(parsed.ready)
        assertFalse(parsed.changed)
    }

    @Test fun aSlideWithoutFragmentsOrNextHasNone() {
        val parsed = assertNotNull(deckReport("""{"kinuvo":"deck","h":0,"v":0,"f":null,"n":1,"total":1,"notes":"","next":null,"changed":true}"""))
        assertNull(parsed.status.position.indexf)
        assertNull(parsed.status.next)
        assertTrue(parsed.changed)
    }

    @Test fun ignoresMessagesThatAreNotDeckReports() {
        assertNull(deckReport("""{"kinuvo":"design-ready"}"""))
        assertNull(deckReport("""{"h":1}"""))
        assertNull(deckReport("not json"))
        assertNull(deckReport("""{"kinuvo":"deck","h":"one"}"""))
    }

    @Test fun refusesImpossibleOrOversizedReports() {
        assertNull(deckReport("""{"kinuvo":"deck","h":-1}"""))
        assertNull(deckReport("""{"kinuvo":"deck","f":-5}"""))
        assertNull(deckReport("""{"kinuvo":"deck","notes":"${"x".repeat(100_001)}"}"""))
        val clamped = assertNotNull(deckReport("""{"kinuvo":"deck","n":99,"total":3,"notes":"${"y".repeat(30_000)}","next":{"title":"${"t".repeat(900)}"}}"""))
        assertEquals(3, clamped.status.number)
        assertEquals(20_000, clamped.status.notes.length)
        assertEquals(300, clamped.status.next?.title?.length)
    }

    @Test fun positionsRoundTripThroughSharedState() {
        for (position in listOf(DeckPosition(), DeckPosition(3, 2, 1), DeckPosition(0, 0, -1))) assertEquals(position, deckPosition(position.toState()))
        assertNull(deckPosition(null))
        assertNull(deckPosition(JsonPrimitive("slide 4")))
        assertNull(deckPosition(Json.parseToJsonElement("""{"indexh":-2}""")))
    }

    @Test fun commandsForThePageNameTheirKindAndPosition() {
        val apply = Json.parseToJsonElement(deckApply(DeckPosition(4, 0, null))).jsonObject
        assertEquals("deck-apply", apply.getValue("kinuvo").jsonPrimitive.content)
        assertEquals(4, apply.getValue("h").jsonPrimitive.content.toInt())
        assertFalse("f" in apply)
        assertEquals(2, (Json.parseToJsonElement(deckApply(DeckPosition(0, 0, 2))) as JsonObject).getValue("f").jsonPrimitive.content.toInt())
        val step = Json.parseToJsonElement(deckStep(false)).jsonObject
        assertEquals("deck-step", step.getValue("kinuvo").jsonPrimitive.content)
        assertEquals("false", step.getValue("forward").jsonPrimitive.content)
    }
}
