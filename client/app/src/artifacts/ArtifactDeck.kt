package kinuvo.app.artifacts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where a reveal.js deck is, as the indices `Reveal.slide` takes; [indexf] is null on a slide with no fragments. This is the state Devices share. */
@Serializable
data class DeckPosition(val indexh: Int = 0, val indexv: Int = 0, val indexf: Int? = null)

/** A slide shortened for the next-slide preview. */
@Serializable
data class DeckSlide(val title: String = "", val text: String = "")

/** What the page's deck adapter last reported: [number] of [total] slides, the current slide's speaker [notes] as plain text, and the [next] slide. */
data class DeckStatus(val position: DeckPosition, val number: Int, val total: Int, val notes: String, val next: DeckSlide?)

/** A report from the page: [ready] when the adapter started, [changed] when the position differs from the last one it sent or applied. */
data class DeckReport(val status: DeckStatus, val ready: Boolean, val changed: Boolean)

@Serializable
private class Wire(
    val kinuvo: String,
    val h: Int = 0,
    val v: Int = 0,
    val f: Int? = null,
    val n: Int = 1,
    val total: Int = 1,
    val notes: String = "",
    val next: DeckSlide? = null,
    val ready: Boolean = false,
    val changed: Boolean = false,
)

private val json = Json { ignoreUnknownKeys = true }

private const val MAX_MESSAGE = 100_000
private const val MAX_NOTES = 20_000
private const val MAX_SLIDE_TEXT = 300

/** The deck adapter's message in [text], or null for anything else: other messages, malformed or oversized ones, which a page script can send too. */
fun deckReport(text: String): DeckReport? {
    if (text.length > MAX_MESSAGE) return null
    val wire = runCatching { json.decodeFromString<Wire>(text) }.getOrNull()?.takeIf { it.kinuvo == "deck" } ?: return null
    if (wire.h < 0 || wire.v < 0 || (wire.f ?: -1) < -1) return null
    val total = wire.total.coerceAtLeast(1)
    val status = DeckStatus(
        DeckPosition(wire.h, wire.v, wire.f),
        wire.n.coerceIn(1, total),
        total,
        wire.notes.trim().take(MAX_NOTES),
        wire.next?.let { DeckSlide(it.title.take(MAX_SLIDE_TEXT), it.text.take(MAX_SLIDE_TEXT)) },
    )
    return DeckReport(status, wire.ready, wire.changed)
}

fun DeckPosition.toState(): JsonElement = json.encodeToJsonElement(DeckPosition.serializer(), this)

/** The shared state as a position, or null when it isn't one. */
fun deckPosition(state: JsonElement?): DeckPosition? =
    state?.let { runCatching { json.decodeFromJsonElement(DeckPosition.serializer(), it) }.getOrNull() }?.takeIf { it.indexh >= 0 && it.indexv >= 0 && (it.indexf ?: -1) >= -1 }

/** What the app tells the page to do: show [position], which the page does not report back; or go [step]. */
fun deckApply(position: DeckPosition) = buildJsonObject {
    put("kinuvo", "deck-apply")
    put("h", position.indexh)
    put("v", position.indexv)
    position.indexf?.let { put("f", it) }
}.toString()

fun deckStep(forward: Boolean) = buildJsonObject { put("kinuvo", "deck-step"); put("forward", forward) }.toString()
