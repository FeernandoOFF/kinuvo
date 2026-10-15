package kinuvo.harness.anytype

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kinuvo.harness.core.knowledge.BodyEdit
import kinuvo.harness.core.knowledge.KnowledgeSource
import kinuvo.harness.core.knowledge.NoteEdit
import kinuvo.harness.core.knowledge.NoteFile
import kinuvo.harness.core.knowledge.Written
import kinuvo.protocol.KnowledgeSchema
import kinuvo.protocol.Note
import kinuvo.protocol.NoteProperty
import kinuvo.protocol.NoteSummary
import kinuvo.protocol.NoteType
import kinuvo.protocol.MAX_ATTACHMENT_BYTES
import kinuvo.protocol.attachmentMimeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Knowledge in an Anytype space, through the local API v2: notes are objects with the space's own types and properties,
 * refs are object ids, and bodies are the objects' blocks as Markdown. Options: `url`, `apiKey` and `space`; [expand]
 * resolves Secret references in the key.
 */
class AnytypeKnowledgeSource private constructor(
    private val options: () -> Map<String, String>,
    private val client: HttpClient,
    private val expand: suspend (String) -> String,
    private val cache: Cache,
    private val lock: Mutex,
) : KnowledgeSource {
    constructor(options: () -> Map<String, String>, client: HttpClient, expand: suspend (String) -> String = { it }) :
        this(options, client, expand, Cache(), Mutex())

    override val id = "anytype"
    override val scope get() = options().let { "anytype:${url(it)}:${it["space"].orEmpty()}" }
    override fun snapshot(): KnowledgeSource = options().toMap().let { config ->
        AnytypeKnowledgeSource({ config }, client, expand, cache, lock)
    }
    override fun configured(options: Map<String, String>, folder: kotlinx.io.files.Path): KnowledgeSource =
        AnytypeKnowledgeSource({ options }, client, expand)

    private class Space(val scope: String, val schema: KnowledgeSchema, val formats: Map<String, String>, val templates: Map<String, Map<String, String>>, val at: TimeSource.Monotonic.ValueTimeMark)
    private class Doc(val id: String, val type: String, val etag: String, val properties: JsonObject, val blocks: List<JsonObject>) {
        val name get() = properties["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    }
    private class Reply(val json: JsonObject, val etag: String?)

    private class Cache { @Volatile var space: Space? = null }
    private val json = Json { ignoreUnknownKeys = true }

    override fun checkOptions(options: Map<String, String>) {
        require(!options["apiKey"].isNullOrBlank() && !options["space"].isNullOrBlank()) { "Anytype needs an API key and space ID" }
        val url = runCatching { java.net.URI(url(options)) }.getOrNull()
        require(url != null && url.scheme in listOf("http", "https") && url.host != null && url.userInfo == null && url.query == null && url.fragment == null) {
            "Give an http(s) API URL without credentials, query or fragment"
        }
    }

    override suspend fun schema() = space().schema

    override suspend fun search(query: String, type: String?, limit: Int): List<NoteSummary> {
        val types = space().schema.types.map { it.key }.toSet()
        val found = mutableListOf<NoteSummary>()
        var offset = 0
        while (found.size < limit) {
            val body = buildJsonObject {
                put("query", query.trim())
                type?.let { put("filter", "type IN (${JsonPrimitive(it)})") }
                putJsonArray("fields") { add("name"); add("snippet") }
            }
            val page = request(HttpMethod.Post, "search", body, params = listOf("offset" to offset, "limit" to PAGE))!!.json
            val data = page["data"]?.jsonArray.orEmpty().map { it.jsonObject }
            found += data.filter { it.text("type") in types }.map { o ->
                val properties = o["properties"]?.jsonObject
                NoteSummary(o.text("id"), o.text("type"), o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    snippet(properties?.get("snippet")?.jsonPrimitive?.contentOrNull.orEmpty(), query.trim()))
            }
            offset += data.size
            if (data.isEmpty() || page["has_more"]?.jsonPrimitive?.booleanOrNull != true) break
        }
        return found.take(limit)
    }

    override suspend fun read(ref: String): Note? = doc(ref)?.let { note(it) }

    override suspend fun file(ref: String): NoteFile? {
        if (!REF.matches(ref)) return null
        val (mime, bytes) = send(HttpMethod.Get, "files/${ref.encodeURLPathPart()}/content") { response ->
            if (response.status.value in listOf(404, 410)) return@send null
            if (!response.status.isSuccess()) fail(response, response.bodyAsText())
            val bytes = response.bodyAsChannel().readBuffer(MAX_ATTACHMENT_BYTES + 1L).readByteArray()
            require(bytes.size <= MAX_ATTACHMENT_BYTES) { "That file is over 10 MB, too large to show" }
            response.contentType()?.withoutParameters()?.toString()?.takeIf { it != "application/octet-stream" } to bytes
        } ?: return null
        val properties = doc(ref)?.properties
        val base = (properties?.get("name")?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: ref).replace(Regex("[/\\\\\\p{Cntrl}]"), "_").take(150)
        val ext = properties?.get("file_ext")?.jsonPrimitive?.contentOrNull?.trim('.')?.ifBlank { null } ?: EXTENSIONS[mime]
        val name = if (ext == null || attachmentMimeType(base) != null) base else "$base.$ext"
        return NoteFile(name, mime ?: attachmentMimeType(name) ?: "application/octet-stream", bytes)
    }

    override suspend fun write(edit: NoteEdit): Written = lock.withLock {
        val space = space()
        val formats = space.formats
        var doc = if (edit.ref == null) create(edit, formats, edit.template?.let { space.templates[edit.type]?.get(it) ?: error("Unknown template '$it'") }) else requireNotNull(doc(edit.ref!!)) { "No note '${edit.ref}'" }
        var etag = doc.etag
        val before = if (edit.ref == null) null else state(doc)
        val undo = mutableListOf<JsonObject>()
        try {
            if (edit.ref == null) doc = reload(doc, etag)
            // A new note only needs the clears, of values its template set.
            val props = if (edit.ref != null) edit else edit.copy(title = null, set = edit.set.filterValues { it.isNullOrBlank() })
            properties(doc, props, formats)?.let { (op, inverse) ->
                etag = patch(doc, op)
                undo.add(0, inverse)
                doc = reload(doc, etag)
            }
            for (e in edit.body) {
                val (op, inverse) = body(doc, e)
                val reply = patchReply(doc, op)
                etag = requireNotNull(reply.etag) { "Anytype didn't return the saved ETag" }
                undo.addAll(0, inverse(reply.json))
                doc = reload(doc, etag)
            }
            val token = buildJsonObject {
                put("id", doc.id)
                put("etag", etag)
                put("state", state(doc))
                put("before", before)
                if (edit.ref == null) put("created", true) else put("ops", JsonArray(undo))
            }
            Written(note(doc), token)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    if (edit.ref == null) {
                        check(doc(doc.id)?.etag == etag) { "The new note changed while saving" }
                        request(HttpMethod.Delete, "objects/${doc.id.encodeURLPathPart()}", ifMatch = etag)
                    } else if (undo.isNotEmpty()) request(HttpMethod.Patch, "objects/${doc.id.encodeURLPathPart()}", buildJsonObject { put("ops", JsonArray(undo)) }, ifMatch = etag, params = listOf("ids" to "full"))
                }.exceptionOrNull()?.let(e::addSuppressed)
            }
            throw e
        }
    }

    override suspend fun undo(token: JsonElement) = lock.withLock {
        val t = token.jsonObject
        val created = t["created"]?.jsonPrimitive?.booleanOrNull == true
        val doc = doc(t.text("id")) ?: if (created) return@withLock else error("The note was deleted in Anytype since; there's nothing to undo")
        if (!created && state(doc) == t["before"]?.jsonPrimitive?.contentOrNull) return@withLock
        check(doc.etag == t.text("etag") || state(doc) == t["state"]?.jsonPrimitive?.contentOrNull) { "${doc.name} changed in Anytype since; undo the newer changes first" }
        if (created) request(HttpMethod.Delete, "objects/${doc.id.encodeURLPathPart()}", ifMatch = doc.etag)
        else t.getValue("ops").jsonArray.map { it.jsonObject }.takeIf { it.isNotEmpty() }?.let { patch(doc, *it.toTypedArray()) }
        Unit
    }

    private suspend fun create(edit: NoteEdit, formats: Map<String, String>, template: String?): Doc {
        val properties = buildJsonObject {
            edit.set.forEach { (key, value) -> if (!value.isNullOrBlank()) put(key, AnytypeProperties.write(key, format(formats, key), value)) }
        }
        val created = request(HttpMethod.Post, "objects", buildJsonObject {
            put("type", requireNotNull(edit.type)); put("name", requireNotNull(edit.title)); put("template", template ?: "none"); put("properties", properties)
        })!!
        return Doc(created.json.text("id"), requireNotNull(edit.type), requireNotNull(created.etag) { "Anytype didn't return the created ETag" },
            JsonObject(properties + ("name" to JsonPrimitive(requireNotNull(edit.title)))), emptyList())
    }

    /** A `set_properties` op for [edit]'s title and properties, and the op that restores their old values. */
    private fun properties(doc: Doc, edit: NoteEdit, formats: Map<String, String>): Pair<JsonObject, JsonObject>? {
        val set = edit.set.filterValues { !it.isNullOrBlank() }.mapValues { (k, v) -> AnytypeProperties.write(k, format(formats, k), v!!) } +
            listOfNotNull(edit.title?.takeIf { it != doc.name }?.let { "name" to JsonPrimitive(it) })
        val unset = edit.set.filterValues { it.isNullOrBlank() }.keys.onEach { format(formats, it) }.filter { it in doc.properties }
        if (set.isEmpty() && unset.isEmpty()) return null
        val keys = set.keys + unset
        return setProperties(set, unset) to setProperties(keys.filter { it in doc.properties }.associateWith { doc.properties.getValue(it) }, keys.filter { it !in doc.properties })
    }

    private fun setProperties(set: Map<String, JsonElement>, unset: Collection<String>) = buildJsonObject {
        put("op", "set_properties")
        if (set.isNotEmpty()) put("set", JsonObject(set))
        if (unset.isNotEmpty()) put("unset", JsonArray(unset.map(::JsonPrimitive)))
    }

    /** The op for [e] on [doc], and how to undo it given the PATCH response. */
    private fun body(doc: Doc, e: BodyEdit): Pair<JsonObject, (JsonObject) -> List<JsonObject>> = when (e) {
        is BodyEdit.Append -> {
            val markdown = AnytypeBody.native(e.markdown.trim())
            val under = e.under?.trim()?.takeIf { it.isNotEmpty() }
            val at = under?.let { AnytypeBody.section(doc.blocks, it) }
            buildJsonObject {
                put("op", "insert_blocks")
                put("markdown", if (under != null && at == null) "## $under\n\n$markdown" else markdown)
                at?.let { (where, id) -> put(where, id) }
            } to { reply ->
                reply["created_blocks"]?.jsonObject.orEmpty().values.map { it.jsonPrimitive.content }.reversed()
                    .map { buildJsonObject { put("op", "delete_block"); put("id", it) } }
            }
        }
        is BodyEdit.Link -> AnytypeBody.link(doc.blocks, e.text, e.target).let { (block, text) -> updateBlock(block, text) }
        is BodyEdit.Replace -> AnytypeBody.replace(doc.blocks, e.old, e.new).let { (block, text) -> updateBlock(block, text) }
    }

    private fun updateBlock(block: JsonObject, text: String): Pair<JsonObject, (JsonObject) -> List<JsonObject>> {
        fun op(text: String) = buildJsonObject { put("op", "update_block"); put("id", block.text("id")); putJsonObject("set") { put("text", text) } }
        return op(text) to { _ -> listOf(op(block["text"]?.jsonPrimitive?.contentOrNull.orEmpty())) }
    }

    private suspend fun note(doc: Doc): Note {
        val formats = space().formats
        val properties = doc.properties.filterKeys { it in formats && it !in AnytypeProperties.SYSTEM }
            .mapNotNull { (k, v) -> AnytypeProperties.read(formats[k], v)?.let { k to it } }.toMap()
        val names = doc.blocks.filter { it["type"]?.jsonPrimitive?.contentOrNull == "link" }.mapNotNull { it["object_id"]?.jsonPrimitive?.contentOrNull }
            .distinct().associateWith { doc(it)?.name ?: it }
        return Note(doc.id, doc.type, doc.name, properties, AnytypeBody.render(doc.blocks, names))
    }

    private suspend fun doc(id: String): Doc? {
        if (!REF.matches(id)) return null
        val reply = request(HttpMethod.Get, "objects/${id.encodeURLPathPart()}", params = listOf("ids" to "full"), missing = true) ?: return null
        val properties = reply.json["properties"]?.jsonObject ?: JsonObject(emptyMap())
        if (properties["is_archived"]?.jsonPrimitive?.booleanOrNull == true || properties["is_deleted"]?.jsonPrimitive?.booleanOrNull == true) return null
        return Doc(reply.json.text("id"), reply.json.text("type"), requireNotNull(reply.etag) { "Anytype didn't return an ETag" },
            properties, reply.json["blocks"]?.jsonArray.orEmpty().map { it.jsonObject })
    }

    /** A hash of what Agents see and change, so a note undone back to how a write left it can be undone further. */
    private fun state(doc: Doc): String {
        fun sorted(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.toSortedMap().mapValues { sorted(it.value) })
            is JsonArray -> JsonArray(e.map(::sorted))
            else -> e
        }
        val properties = doc.properties.filterKeys { it == "name" || it !in AnytypeProperties.SYSTEM }
        val content = sorted(JsonArray(listOf(JsonPrimitive(doc.type), JsonObject(properties), JsonArray(doc.blocks)))).toString()
        return MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun reload(doc: Doc, etag: String) = (doc(doc.id) ?: error("${doc.name} was deleted in Anytype while saving")).also {
        check(it.etag == etag) { "${doc.name} changed in Anytype while saving; read it again and retry" }
    }

    private suspend fun patch(doc: Doc, vararg ops: JsonObject): String = requireNotNull(patchReply(doc, *ops).etag) { "Anytype didn't return the saved ETag" }

    private suspend fun patchReply(doc: Doc, vararg ops: JsonObject) =
        request(HttpMethod.Patch, "objects/${doc.id.encodeURLPathPart()}", buildJsonObject { put("ops", JsonArray(ops.toList())) }, ifMatch = doc.etag, params = listOf("ids" to "full"))!!

    private suspend fun space(): Space {
        val scope = scope
        cache.space?.takeIf { it.scope == scope && it.at.elapsedNow() < 1.minutes }?.let { return it }
        val properties = list("properties")
        val formats = properties.associate { it.text("key") to it.text("format") }
        val visible = properties.filter { it.text("key") !in AnytypeProperties.SYSTEM }
        val keys = visible.map { it.text("key") }.toSet()
        // Templates sharing a name are told apart by id.
        val templates = list("templates").groupBy { it["template_for"]?.jsonPrimitive?.contentOrNull.orEmpty() }.mapValues { (_, ts) ->
            val named = ts.map { (it["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: "Untitled") to it.text("id") }
            named.associate { (name, id) -> (if (named.count { it.first == name } > 1) "$name ($id)" else name) to id }
        }
        val types = list("types").filter { it.text("key") !in HIDDEN_TYPES }.map { t ->
            val key = t.text("key")
            val doc = request(HttpMethod.Get, "types/${key.encodeURLPathPart()}")!!.json
            val defs = doc["type_settings"]?.jsonObject?.get("property_definitions")?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["property"]?.jsonPrimitive?.contentOrNull }.filter { it in keys }
            val description = doc["properties"]?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull.orEmpty()
            NoteType(key, t["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: key, description, defs, templates[key].orEmpty().keys.toList())
        }
        val schema = KnowledgeSchema(types, visible.map { NoteProperty(it.text("key"), it["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: it.text("key"), it.text("format")) })
        return Space(scope, schema, formats, templates, TimeSource.Monotonic.markNow()).also { cache.space = it }
    }

    private fun format(formats: Map<String, String>, key: String): String {
        require(key !in AnytypeProperties.SYSTEM) { "Anytype manages '$key'" }
        return requireNotNull(formats[key]) { "Unknown Anytype property '$key'" }
    }

    private suspend fun list(path: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        do {
            val page = request(HttpMethod.Get, path, params = listOf("offset" to result.size, "limit" to PAGE))!!.json
            val data = page["data"]?.jsonArray.orEmpty().map { it.jsonObject }
            result += data
        } while (data.isNotEmpty() && page["has_more"]?.jsonPrimitive?.booleanOrNull == true)
        return result
    }

    private suspend fun request(method: HttpMethod, path: String, body: JsonObject? = null, ifMatch: String? = null,
                                params: List<Pair<String, Any>> = emptyList(), missing: Boolean = false): Reply? = send(method, path, body, ifMatch, params) { response ->
        if (missing && response.status.value in listOf(404, 410)) return@send null
        val text = response.bodyAsText()
        val data = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (!response.status.isSuccess()) fail(response, text)
        val etag = (response.headers[HttpHeaders.ETag] ?: data?.get("etag")?.jsonPrimitive?.contentOrNull)?.removePrefix("W/")?.trim('"')
        Reply(data ?: JsonObject(emptyMap()), etag)
    }

    /** Sends a request, retrying when rate limited, and has [read] take the response while its body streams. */
    private suspend fun <T> send(method: HttpMethod, path: String, body: JsonObject? = null, ifMatch: String? = null,
                                 params: List<Pair<String, Any>> = emptyList(), read: suspend (HttpResponse) -> T): T {
        val o = options()
        val space = o["space"].orEmpty()
        require(!o["apiKey"].isNullOrBlank() && space.isNotBlank()) { "Configure Anytype's API key and space in Settings → Server → Knowledge" }
        val key = expand(o.getValue("apiKey"))
        var attempt = 0
        while (true) {
            val result = try {
                client.prepareRequest("${url(o)}/v2/spaces/${space.encodeURLPathPart()}/$path") {
                    this.method = method
                    header(HttpHeaders.Authorization, "Bearer $key")
                    ifMatch?.let { header(HttpHeaders.IfMatch, "\"$it\"") }
                    params.forEach { (k, v) -> parameter(k, v) }
                    body?.let { setBody(TextContent(it.toString(), ContentType.Application.Json)) }
                }.execute { response ->
                    if (response.status.value == 429 && attempt < 3) null else Result.success(read(response))
                }
            } catch (e: HttpRequestTimeoutException) {
                throw IllegalStateException("Anytype at ${url(o)} didn't answer in time; check that the Anytype app on the Server isn't stuck", e)
            } catch (e: java.io.IOException) {
                throw IllegalStateException(
                    "Can't reach Anytype at ${url(o)}: open the Anytype app on the Server (its API only runs while it's open), or check the API URL", e,
                )
            }
            if (result != null) return result.getOrThrow()
            delay(1000L * ++attempt)
        }
    }

    private suspend fun fail(response: HttpResponse, text: String): Nothing {
        val key = expand(options().getValue("apiKey"))
        val data = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val detail = listOfNotNull(data?.get("message"), data?.get("issues")?.jsonArray?.firstOrNull()?.jsonObject?.get("message"))
            .joinToString("; ") { it.jsonPrimitive.content }.replace(key, "[redacted]").take(600)
        val hint = when (response.status.value) {
            401 -> "Anytype API key is invalid or revoked; replace it in Settings → Server → Knowledge"
            403 -> "Anytype denied access; check this key's space permissions"
            404 -> "Anytype space or object not found"
            409, 412 -> "The note changed in Anytype while saving; read it again and retry"
            else -> "Anytype request failed (${response.status.value})"
        }
        throw IllegalStateException(hint + detail.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty())
    }

    private fun snippet(text: String, query: String): String {
        val at = if (query.isEmpty()) 0 else text.lowercase().indexOf(query.lowercase()).coerceAtLeast(0)
        return text.substring((at - 40).coerceAtLeast(0)).take(160).replace('\n', ' ').trim()
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:31009"
        private const val PAGE = 100
        private val REF = Regex("[A-Za-z0-9._-]{20,}")
        private val HIDDEN_TYPES = setOf("chat_derived", "participant", "template", "file", "image", "audio", "video", "query", "collection", "object_type", "space")
        private val EXTENSIONS = mapOf(
            "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp", "application/pdf" to "pdf",
            "text/csv" to "csv", "application/json" to "json", "text/plain" to "txt", "text/markdown" to "md",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        )
        private fun url(options: Map<String, String>) = options["url"].orEmpty().ifBlank { DEFAULT_URL }.trimEnd('/')
    }
}
