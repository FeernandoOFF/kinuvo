package kinuvo.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kinuvo.protocol.*

internal class KnowledgeCommands : Group(
    "knowledge", "Search your Knowledge and review or undo what Agents changed in it.",
    SearchKnowledge(), ShowNote(), KnowledgeChanges(), UndoChange(),
)

private class SearchKnowledge : ServerCommand("search", "Find notes by title or text.") {
    private val query by argument("QUERY")
    private val type by option("--type", help = "Only this note type (its key)")

    override suspend fun run() {
        val found = session.call { client.call(Api.KnowledgeSearch, KnowledgeSearchParams(query, type)) }
        echo(table(listOf("REF", "TYPE", "TITLE"), found.map { listOf(it.ref, it.type, it.title) }))
    }
}

private class ShowNote : ServerCommand("show", "Show a note by ref or title.") {
    private val ref by argument("REF")

    override suspend fun run() {
        val n = session.call { client.call(Api.KnowledgeNote, NoteRef(ref)) }
        echo("${n.title} (${n.type}), ref ${n.ref}")
        n.properties.forEach { (k, v) -> echo("$k: $v") }
        if (n.body.isNotBlank()) echo("\n${n.body.trim()}")
    }
}

private class KnowledgeChanges : ServerCommand("changes", "List the latest changes Agents made, newest first.") {
    private val limit by option("-n", "--limit").int().default(20)

    override suspend fun run() {
        val changes = session.call { client.call(Api.KnowledgeChanges, KnowledgeChangesParams(limit)) }
        echo(table(listOf("ID", "WHEN", "BY", "CHANGE"), changes.map { c ->
            listOf(c.id.take(8), time(c.at, "UTC"), c.agentId ?: "-", c.summary + if (c.undone) " (undone)" else "")
        }))
    }
}

private class UndoChange : ServerCommand("undo", "Undo a change (give the ID or its first characters).") {
    private val id by argument("ID")

    override suspend fun run() {
        val undone = session.call {
            val full = if (id.length >= 36) id
            else client.call(Api.KnowledgeChanges, KnowledgeChangesParams(200)).filter { it.id.startsWith(id) }.singleOrNull()?.id
                ?: error("No single recent change matches '$id'; give the full ID for older ones")
            client.call(Api.UndoKnowledgeChange, KnowledgeChangeRef(full))
        }
        echo("Undone: ${undone.summary}")
    }
}
