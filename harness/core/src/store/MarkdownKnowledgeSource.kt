package kinuvo.harness.core.store

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import kinuvo.harness.core.knowledge.KnowledgeConfig
import kinuvo.harness.core.knowledge.KnowledgeSource
import kinuvo.harness.core.knowledge.MarkdownBody
import kinuvo.harness.core.knowledge.NoteEdit
import kinuvo.harness.core.knowledge.Written
import kinuvo.harness.core.skills.splitFrontmatter
import kinuvo.protocol.KnowledgeSchema
import kinuvo.protocol.Note
import kinuvo.protocol.NoteProperty
import kinuvo.protocol.NoteSummary
import kinuvo.protocol.NoteType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Knowledge as an Obsidian-style folder of Markdown notes. A note's ref is its path without `.md`, its title the file
 * name, its type the `type` frontmatter (`note` without one), its properties the other scalar frontmatter, and links are
 * `[[ref|label]]` wikilinks. New notes go to their type's folder; types live in `.kinuvo/types.yaml`. Frontmatter Kinuvo
 * doesn't change (lists, maps, comments, untouched values) is kept as written. [folder] is read on every call.
 */
class MarkdownKnowledgeSource(private val folder: () -> Path) : KnowledgeSource {
    constructor(root: Path) : this({ root })

    override val id = KnowledgeConfig.MARKDOWN
    override val scope get() = "markdown:$root"
    override fun snapshot(): KnowledgeSource = MarkdownKnowledgeSource(root)
    override fun configured(options: Map<String, String>, folder: Path): KnowledgeSource = MarkdownKnowledgeSource(folder)

    private val lock = Mutex()
    private val root get() = folder()
    private val typesFile get() = Path(root, ".kinuvo", "types.yaml")

    @Serializable
    private data class StoredType(val name: String, val description: String = "", val fields: List<String> = emptyList(), val folder: String)

    override suspend fun schema(): KnowledgeSchema {
        val notes = all()
        val stored = stored()
        val seen = notes.map { it.type }.distinct().filter { t -> stored.none { it.name == t } }
        val types = stored.map { NoteType(it.name, it.name, it.description, it.fields) } + seen.map { NoteType(it) }
        val keys = (stored.flatMap { it.fields } + notes.flatMap { it.properties.keys }).distinct()
        return KnowledgeSchema(types, keys.map { NoteProperty(it) }, open = true)
    }

    override suspend fun search(query: String, type: String?, limit: Int): List<NoteSummary> {
        val q = query.trim().lowercase()
        return all().filter { type == null || it.type == type }.mapNotNull { n ->
            val score = when {
                n.title.lowercase() == q -> 3
                q in n.title.lowercase() -> 2
                q.isEmpty() || q in n.properties.values.joinToString(" ").lowercase() || q in n.body.lowercase() -> 1
                else -> 0
            }
            if (score > 0) score to n else null
        }.sortedWith(compareByDescending<Pair<Int, Note>> { it.first }.thenBy { it.second.title }).take(limit).map { (_, n) ->
            NoteSummary(n.ref, n.type, n.title, snippet(n.body, q))
        }
    }

    override suspend fun read(ref: String): Note? = fileFor(ref)?.readTextOrNull()?.let { parse(ref, it) }

    override suspend fun write(edit: NoteEdit): Written = lock.withLock {
        val ref = edit.ref ?: freeRef(folderFor(requireNotNull(edit.type)), requireNotNull(edit.title))
        val file = requireNotNull(fileFor(ref)) { "Invalid ref '$ref'" }
        val before = file.readTextOrNull()
        if (edit.ref != null) {
            requireNotNull(before) { "No note '$ref'" }
            require(edit.title == null || edit.title == ref.substringAfterLast('/')) { "Renaming a Markdown note changes its file; rename it in your editor" }
        }
        val (raw, content) = before?.let(::splitFrontmatter) ?: (null to "")
        val fields = frontmatter(raw, edit.type.takeIf { before == null }, edit.set)
        val body = if (edit.body.isEmpty() && before != null) content else MarkdownBody.apply(content, edit.body).let {
            (if (fields.isEmpty() || it.isEmpty()) "" else "\n") + it + "\n"
        }
        val after = if (fields.isEmpty()) body else "---\n$fields---\n$body"
        writeAtomically(file, after)
        val note = requireNotNull(parse(ref, after)) { "Couldn't read back $ref" }
        Written(note, buildJsonObject { put("ref", ref); put("before", before); put("after", after) })
    }

    override suspend fun undo(token: JsonElement) = lock.withLock {
        val t = token.jsonObject
        val ref = t.getValue("ref").jsonPrimitive.content
        val file = requireNotNull(fileFor(ref)) { "Invalid ref '$ref'" }
        val before = t["before"]?.jsonPrimitive?.contentOrNull
        val current = file.readTextOrNull()
        if (current == before) return@withLock
        check(current == t["after"]?.jsonPrimitive?.contentOrNull) { "${ref.substringAfterLast('/')} changed since; undo the newer changes first" }
        if (before == null) SystemFileSystem.delete(file, mustExist = false) else writeAtomically(file, before)
    }

    private fun stored(): List<StoredType> = typesFile.readTextOrNull()?.let { Yaml.default.decodeFromString(ListSerializer(StoredType.serializer()), it) }
        ?: DEFAULT_TYPES.map { (name, description) -> StoredType(name, description, folder = FOLDERS[name] ?: name) }

    /** Every note in the folder and its subfolders, except hidden ones. */
    private fun all(): List<Note> {
        val visited = mutableSetOf<Path>()
        fun walk(dir: Path, prefix: String): List<Note> = dir.children().filterNot { it.name.startsWith(".") }.flatMap { p ->
            when {
                !isInside(root, p) -> emptyList()
                p.isDirectory() -> if (visited.add(SystemFileSystem.resolve(p))) walk(p, "$prefix${p.name}/") else emptyList()
                p.name.endsWith(".md") -> listOfNotNull(p.readTextOrNull()?.let { parse(prefix + p.name.removeSuffix(".md"), it) })
                else -> emptyList()
            }
        }
        if (root.exists()) visited.add(SystemFileSystem.resolve(root))
        return walk(root, "")
    }

    /** The file for [ref], or null when [ref] isn't a relative path that stays inside the folder, symlinks included. */
    private fun fileFor(ref: String): Path? {
        val parts = ref.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it.startsWith(".") }) return null
        return Path(root, *parts.dropLast(1).toTypedArray(), parts.last() + ".md").takeIf { isInside(root, it) }
    }

    /** `Folder/Title`, or `Folder/Title (2)` and so on when that note exists. */
    private fun freeRef(folder: String, title: String): String {
        val base = title.trim().replace(Regex("""[\\/:*?"<>|#^\[\]]"""), "-").take(120)
        require(base.isNotBlank()) { "title is required" }
        return generateSequence(1) { it + 1 }.map { n -> (if (folder.isEmpty()) "" else "$folder/") + if (n == 1) base else "$base ($n)" }
            .first { requireNotNull(fileFor(it)) { "Folder '$folder' leads outside the knowledge folder" }.exists().not() }
    }

    private fun folderFor(type: String) =
        stored().firstOrNull { it.name == type }?.folder ?: FOLDERS[type] ?: type.replaceFirstChar { it.uppercase() }

    private fun parse(ref: String, text: String): Note? {
        val (raw, content) = splitFrontmatter(text)
        val fields = raw?.takeIf { it.isNotBlank() }?.let { runCatching { Yaml.default.parseToYamlNode(it) as? YamlMap }.getOrNull() ?: return null }
        return Note(
            ref = ref,
            type = fields?.get<YamlScalar>("type")?.content ?: NOTE,
            title = ref.substringAfterLast('/'),
            properties = fields?.let(::scalars).orEmpty(),
            body = content.trim(),
        )
    }

    private fun scalars(fields: YamlMap) =
        fields.entries.filterKeys { it.content != "type" }.mapNotNull { (k, v) -> (v as? YamlScalar)?.let { k.content to it.content } }.toMap()

    /** [raw] frontmatter with [type] set (for a new note) and [set] applied; everything else stays as written. */
    private fun frontmatter(raw: String?, type: String?, set: Map<String, String?>): String {
        val blocks = raw?.let(::blocks).orEmpty().toMutableList()
        fun put(key: String, value: String?) {
            val i = blocks.indexOfFirst { it.first == key }
            val line = value?.let { "$key: ${Json.encodeToString(it)}\n" }
            when {
                i >= 0 && line == null -> blocks.removeAt(i)
                i >= 0 -> blocks[i] = key to line!!
                line != null -> blocks += key to line
            }
        }
        type?.let { put("type", it) }
        set.forEach { (k, v) ->
            require(KEY.matches(k) && k != "type") { "Invalid property name '$k'" }
            put(k, v?.takeIf { it.isNotBlank() })
        }
        return blocks.joinToString("") { it.second }
    }

    /** Frontmatter split into top-level entries, each with the lines that follow it; leading lines have no key. */
    private fun blocks(raw: String): List<Pair<String?, String>> {
        val blocks = mutableListOf<Pair<String?, StringBuilder>>()
        for (line in raw.lines().dropLastWhile { it.isEmpty() }) {
            val key = TOP_KEY.find(line)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
            if (key != null || line.startsWith("#") || blocks.isEmpty()) blocks += key to StringBuilder()
            blocks.last().second.append(line).append('\n')
        }
        return blocks.map { it.first to it.second.toString() }
    }

    private fun snippet(body: String, q: String): String {
        val at = if (q.isEmpty()) 0 else body.lowercase().indexOf(q).coerceAtLeast(0)
        return body.substring((at - 40).coerceAtLeast(0)).take(120).replace('\n', ' ').trim()
    }

    private companion object {
        const val NOTE = "note"
        val TOP_KEY = Regex("""^([^\s#-][^:]*):(\s|$)""")
        val KEY = Regex("[A-Za-z][A-Za-z0-9 _-]{0,63}")
        val DEFAULT_TYPES = listOf(
            "person" to "Someone the user knows",
            "project" to "Something the user is working on",
            "organization" to "A company, team or institution",
            "place" to "A home, venue, city or address",
            "topic" to "An interest or area of the user's life",
            "event" to "Something that happened or will happen",
        )
        val FOLDERS = mapOf(
            "person" to "People", "project" to "Projects", "organization" to "Organizations", "place" to "Places",
            "topic" to "Topics", "event" to "Events",
        )
    }
}
