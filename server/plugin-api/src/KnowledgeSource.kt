package kinuvo.server.plugin

import kinuvo.protocol.knowledge.KnowledgeSchema
import kinuvo.protocol.knowledge.Note
import kinuvo.protocol.knowledge.NoteSummary
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement

/** A change to the body of a note. Links in [markdown] read `[[ref|label]]`; the source writes them its own way. */
sealed interface BodyEdit {
    /** Adds [markdown] at the end, or at the end of the section headed [under] (created when missing). */
    data class Append(val markdown: String, val under: String? = null) : BodyEdit

    /** Turns the first plain occurrence of [text] into a link to [target]. */
    data class Link(val text: String, val target: String) : BodyEdit

    /** Replaces [old], which must occur exactly once, with [new]. */
    data class Replace(val old: String, val new: String) : BodyEdit
}

/**
 * A note to create ([ref] null) or change: [set] sets properties by key (null clears one), [body] edits its text. A new note
 * starts from its type's [template] (one of [NoteType.templates]) when given, and [body] adds to it.
 */
data class NoteEdit(
    val ref: String?,
    val type: String? = null,
    val title: String? = null,
    val set: Map<String, String?> = emptyMap(),
    val body: List<BodyEdit> = emptyList(),
    val template: String? = null,
)

/** A file in Knowledge, e.g. an image or PDF in a note's body. */
class NoteFile(val name: String, val mimeType: String, val bytes: ByteArray)

/** What a write saved, and how the source can revert it. */
data class Written(val note: Note, val undo: JsonElement)

/**
 * Where the user's Knowledge lives, in its own terms: a folder of Markdown notes, an Anytype space, and so on. [id] is what
 * `provider:` names in `config.yaml`'s `knowledge`; [scope] tells its spaces apart, so a change is only undone where it was made.
 */
interface KnowledgeSource {
    val id: String
    val scope: String get() = id
    /** Keeps one operation in the same folder or space when settings change. */
    fun snapshot(): KnowledgeSource = this
    /** Checks this source's settings from the config before they're saved. */
    fun checkOptions(options: Map<String, String>) {}
    /** This source with other settings ([options], or the Markdown [folder]), e.g. to test them before they're saved. */
    fun configured(options: Map<String, String>, folder: Path): KnowledgeSource = this
    suspend fun schema(): KnowledgeSchema
    /** Notes whose title or text match [query], best first; a blank [query] lists notes. */
    suspend fun search(query: String, type: String? = null, limit: Int = 10): List<NoteSummary>
    suspend fun read(ref: String): Note?
    /** The file [ref] links to from a note's body; null when there's none. */
    suspend fun file(ref: String): NoteFile? = null
    /** Applies [edit] (with types and property keys already checked) and returns the note as saved. */
    suspend fun write(edit: NoteEdit): Written
    /** Reverts a write from its [Written.undo]; fails when the note changed since. */
    suspend fun undo(token: JsonElement)
}
