package kinuvo.protocol

import kotlinx.serialization.Serializable

/** A property notes can have, as the knowledge provider defines it; [format] is e.g. text, number, date or objects. */
@Serializable
data class NoteProperty(val key: String, val name: String = key, val format: String = "text")

/** A kind of note in the user's system, e.g. Anytype's Human (`profile`), with the properties it usually has and the names of its templates. */
@Serializable
data class NoteType(val key: String, val name: String = key, val description: String = "", val properties: List<String> = emptyList(), val templates: List<String> = emptyList())

/** The types and properties of the user's knowledge system, in its own terms; with [open], new ones may be used too. */
@Serializable
data class KnowledgeSchema(val types: List<NoteType> = emptyList(), val properties: List<NoteProperty> = emptyList(), val open: Boolean = false)

/**
 * A note in the user's Knowledge, in the provider's own terms: its [type] key, [properties] by key, and a Markdown
 * [body] whose links to other notes read `[[ref|label]]`. [ref] is the provider's stable id.
 */
@Serializable
data class Note(val ref: String, val type: String, val title: String, val properties: Map<String, String> = emptyMap(), val body: String = "")

@Serializable
data class NoteSummary(val ref: String, val type: String, val title: String, val snippet: String = "")

/** One change an Agent made to Knowledge, kept so the user can undo it while [ref] hasn't changed since. */
@Serializable
data class KnowledgeChange(
    val id: String,
    val at: Long,
    val summary: String,
    val ref: String,
    val title: String,
    val agentId: String? = null,
    val sessionId: String? = null,
    val undone: Boolean = false,
    /** The provider and space the change was made in. */
    val scope: String,
    /** How the provider reverts it; only it reads this. */
    val undo: kotlinx.serialization.json.JsonElement,
)

@Serializable
data class KnowledgeSearchParams(val query: String, val type: String? = null, val limit: Int = 20)

@Serializable
data class NoteRef(val ref: String)

@Serializable
data class KnowledgeChangesParams(val limit: Int = 50)

@Serializable
data class KnowledgeChangeRef(val changeId: String)

/**
 * `knowledge.yaml`: the [provider] keeping Knowledge, the Markdown folder ([path], null for the default), other providers'
 * [options] (secret ones come back as [SECRET] and are kept when saved unchanged), whether Agents reflect after
 * conversations, and the user's [conventions] for each provider: how they organize notes, in their words.
 */
@Serializable
data class KnowledgeSettings(
    val path: String? = null,
    val reflection: Boolean = true,
    val provider: String = "markdown",
    val options: Map<String, String> = emptyMap(),
    val conventions: Map<String, String> = emptyMap(),
    val reflectionPrompt: String? = null,
) {
    companion object {
        const val SECRET = "••••••••"
    }
}

/** What testing unsaved [KnowledgeSettings] found: e.g. "12 types, 40 properties", or why it couldn't read them. */
@Serializable
data class KnowledgeTestResult(val summary: String? = null, val error: String? = null)

/** The settings, the Markdown folder they resolve to, and the providers this Server has. */
@Serializable
data class KnowledgeSetup(val settings: KnowledgeSettings, val folder: String, val providers: List<String> = listOf("markdown"))
