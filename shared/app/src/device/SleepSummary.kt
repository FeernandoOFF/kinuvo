package kinuvo.app.device

import kinuvo.app.connection.*

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kinuvo.protocol.ProtocolJson
import kotlin.time.Instant

@Serializable
internal data class SleepSample(val start: String, val end: String, val stage: String, val source: String, val sourceId: String)

/** Union samples before counting; choose the source with the most sleep in each overlapping session. */
internal fun sleepSummary(data: JsonObject, timeZone: String, rangeStart: String? = null, rangeEnd: String? = null): JsonObject {
    data class Sample(val start: Instant, val end: Instant, val raw: SleepSample)
    val recorded = ProtocolJson.decodeFromJsonElement(ListSerializer(SleepSample.serializer()), data.getValue("samples"))
        .map { Sample(Instant.parse(it.start), Instant.parse(it.end), it) }.filter { it.end > it.start }
    val samples = recorded.groupBy { it.raw.sourceId }.values.flatMap { source ->
        val points = source.flatMap { listOf(it.start, it.end) }.distinct().sorted()
        points.zipWithNext().mapNotNull { (start, end) ->
            val active = source.filter { it.start <= start && it.end >= end }
            val selected = if (active.any { it.raw.stage == "awake" }) null else
                listOf("deep", "rem", "core", "asleep").firstNotNullOfOrNull { stage -> active.firstOrNull { it.raw.stage == stage } }
            selected?.let { Sample(start, end, it.raw) }
        }
    }.sortedBy { it.start }
    val sessions = mutableListOf<MutableList<Sample>>()
    for (sample in samples) {
        val last = sessions.lastOrNull()
        if (last == null || (sample.start - last.maxOf { it.end }).inWholeHours >= 3) sessions += mutableListOf(sample)
        else last += sample
    }
    val summaries = sessions.mapNotNull { session ->
        val sources = session.groupBy { it.raw.sourceId }
        val totals = sources.mapValues { (_, samples) -> samples.groupBy { it.raw.stage }.mapValues { (_, intervals) ->
            intervals.sumOf { (it.end - it.start).inWholeMilliseconds / 60_000.0 }
        } }
        val chosen = totals.entries.sortedBy { it.key }.maxByOrNull { it.value.values.sum() }?.takeIf { it.value.values.sum() > 0 } ?: return@mapNotNull null
        val selected = sources.getValue(chosen.key)
        val start = selected.minOf { it.start }
        val end = selected.maxOf { it.end }
        val zone = TimeZone.of(timeZone)
        val wakeDate = end.toLocalDateTime(zone).date
        if (rangeStart != null && wakeDate < Instant.parse(rangeStart).toLocalDateTime(zone).date) return@mapNotNull null
        if (rangeEnd != null && wakeDate >= Instant.parse(rangeEnd).toLocalDateTime(zone).date) return@mapNotNull null
        buildJsonObject {
            put("start", start.toString()); put("end", end.toString())
            put("wakeDate", wakeDate.toString())
            put("sleepMinutes", chosen.value.values.sum())
            put("stagesMinutes", buildJsonObject { chosen.value.forEach { (stage, minutes) -> put(stage, minutes) } })
            put("source", selected.first().raw.source); put("sourceId", chosen.key)
            put("otherSources", JsonArray(sources.filterKeys { it != chosen.key }.values.map { JsonPrimitive(it.first().raw.source) }.distinct()))
        }
    }
    return buildJsonObject {
        put("source", "HealthKit"); put("sessions", JsonArray(summaries))
        put("timeZone", timeZone)
        put("aggregation", "Overlapping samples counted once; awake and in-bed time excluded. Each session uses the source with the most recorded sleep; gaps of 3 hours start another session.")
        data["truncated"]?.let { put("truncated", it) }
        put("availability", "Empty results may mean no recorded samples or no read access. Sleep records may be incomplete until the source syncs.")
    }
}
