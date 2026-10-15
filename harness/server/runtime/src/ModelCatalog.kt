package kinuvo.harness.server

import kinuvo.harness.core.ModelProvider
import kinuvo.harness.core.store.readTextOrNull
import kinuvo.harness.core.store.writeAtomically
import kinuvo.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
private data class CachedModels(val at: Long, val models: List<ModelInfo>)

@Serializable
private data class ModelCache(
    val metadataAt: Long = 0,
    val metadata: JsonObject = JsonObject(emptyMap()),
    val providers: Map<String, CachedModels> = emptyMap(),
)

class ModelCatalog(
    private val file: Path,
    private val providers: List<ModelProvider>,
    private val fetchMetadata: suspend () -> JsonObject,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private var cache = runCatching { file.readTextOrNull()?.let { ProtocolJson.decodeFromString<ModelCache>(it) } }.getOrNull() ?: ModelCache()
    private val attempted = mutableMapOf<String, Long>()
    private val failed = mutableSetOf<String>()

    suspend fun list(refresh: Boolean = false): ModelsCatalog = withContext(Dispatchers.IO) { lock.withLock {
        val now = clock()
        var dirty = false
        if (providers.any { it.catalogId != null } && (refresh || due("metadata", cache.metadataAt, DAY, now))) {
            attempted["metadata"] = now
            val metadata = attempt { fetchMetadata() }
            if (metadata != null) {
                cache = cache.copy(metadataAt = now, metadata = metadata)
                failed -= "metadata"
                dirty = true
            } else failed += "metadata"
        }
        val result = providers.map { provider ->
            var saved = cache.providers[provider.id]
            if (refresh || due(provider.id, saved?.at, HOUR, now)) {
                attempted[provider.id] = now
                val models = attempt { provider.models().distinctBy { it.id } }
                if (models != null) {
                    saved = CachedModels(now, models)
                    cache = cache.copy(providers = cache.providers + (provider.id to saved))
                    failed -= provider.id
                    dirty = true
                } else failed += provider.id
            }
            val stale = saved == null || now - saved.at >= HOUR || provider.id in failed
            ProviderModels(provider.id, provider.name, saved?.models.orEmpty().map { enrich(it, provider.catalogId) }, stale,
                if (stale) "Couldn't refresh models; saved models and custom IDs remain available." else null)
        }
        if (dirty) writeAtomically(file, ProtocolJson.encodeToString(cache), private = true)
        ModelsCatalog(result, if ("metadata" in failed || (providers.any { it.catalogId != null } && now - cache.metadataAt >= DAY))
            "Models.dev is unavailable; using saved model details." else null)
    } }

    private fun due(key: String, at: Long?, ttl: Long, now: Long) =
        (key in failed || at == null || at == 0L || now - at >= ttl) && (attempted[key]?.let { now - it >= 60_000 } != false)

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        withTimeoutOrNull(10_000) { block() }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    private fun enrich(model: ModelInfo, provider: String?): ModelInfo {
        val data = (provider?.let { cache.metadata[it] } as? JsonObject)?.get("models") as? JsonObject
        val m = data?.get(model.id) as? JsonObject ?: return model
        val reasoning = (m["reasoning_options"] as? JsonArray)?.firstOrNull { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "effort" }
            ?.jsonObject?.get("values") as? JsonArray
        val cost = m["cost"] as? JsonObject
        return model.copy(
            name = if (model.name == model.id) (m["name"] as? JsonPrimitive)?.contentOrNull ?: model.name else model.name,
            reasoning = model.reasoning ?: reasoning?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: (m["reasoning"] as? JsonPrimitive)?.booleanOrNull?.takeIf { !it }?.let { emptyList() },
            input = model.input ?: ((m["modalities"] as? JsonObject)?.get("input") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            tools = model.tools ?: (m["tool_call"] as? JsonPrimitive)?.booleanOrNull,
            context = model.context ?: ((m["limit"] as? JsonObject)?.get("context") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
            cost = model.cost ?: cost?.let { ModelCost((it["input"] as? JsonPrimitive)?.doubleOrNull, (it["output"] as? JsonPrimitive)?.doubleOrNull) },
        )
    }

    private companion object {
        const val HOUR = 60 * 60_000L
        const val DAY = 24 * HOUR
    }
}
