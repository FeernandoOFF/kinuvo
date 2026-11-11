package kinuvo.server.knowledge

import kinuvo.server.plugin.BodyEdit
import kinuvo.server.plugin.KnowledgeSource
import kinuvo.server.plugin.NoteEdit
import kinuvo.server.plugin.NoteFile
import kinuvo.server.update
import kinuvo.protocol.knowledge.KnowledgeChange
import kinuvo.protocol.knowledge.KnowledgeSchema
import kinuvo.protocol.knowledge.Note
import kinuvo.protocol.knowledge.NoteSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.files.Path
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Every change Agents made to Knowledge, newest last. */
interface KnowledgeChangeLog {
    suspend fun add(change: KnowledgeChange)
    suspend fun list(limit: Int): List<KnowledgeChange>
    suspend fun get(id: String): KnowledgeChange?
    suspend fun markUndone(id: String)
}

/** Who made a change: the Agent and the Session it came from. */
data class ChangeSource(val agentId: String? = null, val sessionId: String? = null, val changeId: String? = null)

/**
 * The user's Knowledge, through whichever [KnowledgeSource] the config picks. Notes keep the source's own types and
 * properties; how the user organizes them is in the provider's [conventions], for Agents to follow. Edits are checked
 * against the schema, `[[title]]` links resolved to refs, and every change logged so the user can undo it.
 */
@OptIn(ExperimentalAtomicApi::class)
class Knowledge(
    private val sources: () -> KnowledgeSource,
    private val changes: KnowledgeChangeLog,
    val conventions: () -> String = { "" },
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val updateConventions: (String, String, String) -> Unit = { _, _, _ -> error("Conventions cannot be edited") },
) {
    private val lock = Mutex()
    private val listeners = AtomicReference(emptyList<(KnowledgeChange) -> Unit>())

    fun onChange(listener: (KnowledgeChange) -> Unit) = listeners.update { it + listener }

    private val source get() = sources().snapshot()

    fun scope() = source.scope
    fun provider() = source.id

    suspend fun saveConventionsReviewed(provider: String, before: String, content: String) = lock.withLock {
        check(source.id == provider) { "The Knowledge provider changed; review a fresh proposal" }
        val current = conventions()
        if (current == content) return@withLock
        check(current == before) { "Conventions changed; review a fresh proposal" }
        updateConventions(provider, before, content)
    }

    suspend fun schema() = source.schema()

    suspend fun search(query: String, type: String? = null, limit: Int = 10): List<NoteSummary> {
        val source = source
        val key = type?.let { resolve(it, source.schema().types.map { t -> t.key to t.name }) ?: it }
        return source.search(query, key, limit)
    }

    /** A note by ref, or by exact title (of [type], when given). */
    suspend fun find(ref: String, type: String? = null): Note? = find(source, ref, type)

    suspend fun file(ref: String): NoteFile = source.file(ref) ?: error("No file '$ref'; use the ref of a file linked from a note's body")

    suspend fun create(
        type: String, title: String, set: Map<String, String?> = emptyMap(), body: String = "", source: ChangeSource = ChangeSource(),
        under: String? = null, template: String? = null,
    ): Note = write(NoteEdit(null, type, title.trim(), set, listOfNotNull(body.takeIf { it.isNotBlank() }?.let { BodyEdit.Append(it, under) }), template), source)

    /** Changes the note [ref] (a ref or title): its title, properties and body. */
    suspend fun update(ref: String, title: String? = null, set: Map<String, String?> = emptyMap(), body: List<BodyEdit> = emptyList(), source: ChangeSource = ChangeSource()): Note =
        write(NoteEdit(ref, title = title?.trim()?.takeIf { it.isNotEmpty() }, set = set, body = body), source)

    suspend fun changes(limit: Int = 50) = changes.list(limit)

    suspend fun change(id: String) = changes.get(id)

    /** Reverts a change, in the source it was made in, unless the note changed again since. */
    suspend fun undo(changeId: String): KnowledgeChange = lock.withLock {
        val change = changes.get(changeId) ?: error("No change '$changeId'")
        check(!change.undone) { "Already undone" }
        val source = source
        check(change.scope == source.scope) { "Switch Knowledge back to where this change was made before undoing it" }
        source.undo(change.undo)
        withContext(NonCancellable) { changes.markUndone(changeId) }
        change.copy(undone = true).also { notify(it) }
    }

    suspend fun reviewed(edit: NoteEdit, by: ChangeSource, scope: String, expected: Note?): Note =
        write(edit, by, scope, expected)

    private suspend fun write(edit: NoteEdit, by: ChangeSource, reviewScope: String? = null, expected: Note? = null): Note = lock.withLock {
        val source = source
        val schema = source.schema()
        val old = edit.ref?.let { find(source, it) ?: error("No note '$it'") }
        if (reviewScope != null) {
            check(source.scope == reviewScope) { "Knowledge location changed since this proposal. Reject it and reflect again." }
            check(old == expected) { "Note changed since this proposal. Reject it and reflect again." }
            if (old == null) check(find(source, requireNotNull(edit.title), edit.type) == null) {
                "A Note with this title now exists. Reject this proposal and reflect again."
            }
        }
        if (old != null) require(edit.title != null || edit.set.isNotEmpty() || edit.body.isNotEmpty()) { "Give a change to make to the note" }
        val type = edit.type ?: old?.type
        val resolvedType = type?.let { resolve(it, schema.types.map { t -> t.key to t.name }) }
        if (old == null) {
            require(!edit.title.isNullOrBlank()) { "title is required" }
            require(!type.isNullOrBlank() && (schema.open || resolvedType != null)) {
                "Unknown type '$type'; types: ${schema.types.joinToString { "${it.name} (${it.key})" }}"
            }
        }
        val template = edit.template?.let { name ->
            require(old == null) { "A template only applies to a new note" }
            val templates = schema.types.firstOrNull { it.key == resolvedType }?.templates.orEmpty()
            templates.firstOrNull { it == name } ?: templates.singleOrNull { it.equals(name, true) }
                ?: error("Unknown template '$name' for $type; templates: ${templates.joinToString().ifEmpty { "none" }}")
        }
        val set = edit.set.mapKeys { (key, _) ->
            resolve(key, schema.properties.map { it.key to it.name })
                ?: key.takeIf { schema.open }
                ?: error("Unknown property '$key'; properties: ${schema.properties.joinToString { "${it.name} (${it.key})" }}")
        }
        require(set.size == edit.set.size) { "Several names address the same property; use its key once" }
        val body = edit.body.map { e ->
            when (e) {
                is BodyEdit.Append -> {
                    require(e.markdown.isNotBlank()) { "Give the Markdown to append" }
                    e.copy(markdown = links(source, e.markdown))
                }
                is BodyEdit.Replace -> e.copy(new = links(source, e.new))
                is BodyEdit.Link -> e.copy(target = find(source, e.target)?.ref ?: error("No note '${e.target}' to link to"))
            }
        }
        val written = source.write(edit.copy(ref = old?.ref, type = resolvedType ?: type, set = set, body = body, template = template))
        val note = written.note
        val summary = when {
            old == null -> "Created ${note.title}"
            body.isNotEmpty() && set.isEmpty() -> "${note.title}: ${describe(body)}"
            else -> "Updated ${note.title}"
        }
        val change = KnowledgeChange(by.changeId ?: Uuid.random().toString(), clock(), summary.take(200), note.ref, note.title, by.agentId, by.sessionId, scope = source.scope, undo = written.undo)
        withContext(NonCancellable) {
            try {
                changes.add(change)
            } catch (e: Throwable) {
                runCatching { source.undo(written.undo) }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
        }
        notify(change)
        note
    }

    private suspend fun find(source: KnowledgeSource, ref: String, type: String? = null): Note? {
        val key = type?.let { resolve(it, source.schema().types.map { t -> t.key to t.name }) ?: it }
        source.read(ref)?.takeIf { key == null || it.type == key }?.let { return it }
        val matches = source.search(ref, key, 20).filter { (key == null || it.type == key) && it.title.equals(ref, true) }
        require(matches.size <= 1) { "Several notes are titled '$ref'; use a ref from search" }
        return matches.singleOrNull()?.let { source.read(it.ref) }
    }

    private fun resolve(value: String, names: List<Pair<String, String>>): String? {
        names.firstOrNull { it.first == value }?.let { return it.first }
        val matches = names.filter { it.second.equals(value, true) }
        require(matches.size <= 1) { "Ambiguous name '$value'; use its schema key" }
        return matches.singleOrNull()?.first
    }

    /** `[[title]]` and `[[title|label]]` links to notes by title become `[[ref|label]]`; unknown targets are refused. */
    private suspend fun links(source: KnowledgeSource, markdown: String): String {
        var result = markdown
        LINK.findAll(markdown).map { it.groupValues }.distinct().forEach { (whole, target, label) ->
            val note = find(source, target.trim()) ?: error("No note '${target.trim()}' to link to; search for it first")
            result = result.replace(whole, "[[${note.ref}|${label.ifEmpty { note.title }}]]")
        }
        return result
    }

    private fun describe(body: List<BodyEdit>) = body.joinToString("; ") { e ->
        when (e) {
            is BodyEdit.Append -> e.markdown.lines().first { it.isNotBlank() }.trim().replace(MARKER, "").replace(MD_LINK, "$1")
            is BodyEdit.Link -> "linked ${e.text}"
            is BodyEdit.Replace -> "edited text"
        }
    }

    private fun notify(change: KnowledgeChange) = listeners.load().forEach { runCatching { it(change) } }

    companion object {
        private val LINK = Regex("""\[\[([^\]|]+)(?:\|([^\]]*))?]]""")
        private val MARKER = Regex("""^(#+|[-*+])\s+""")
        private val MD_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    }
}
