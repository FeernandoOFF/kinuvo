package kinuvo.harness.server.testing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class ModuleEdgesTest {
    private data class Edge(val from: String, val to: String, val test: Boolean) {
        override fun toString() = "$from -> $to" + if (test) " [test]" else ""
    }

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "project.yaml").isFile }
    private val allowlist = File(root, "harness/server/server-testing/test/module-edges.txt")

    private fun modules() = File(root, "project.yaml").readLines().filter { it.startsWith("  - ") }.map { it.removePrefix("  - ").trim() }

    private fun edges(module: String): List<Edge> {
        var section: String? = null
        val edges = mutableListOf<Edge>()
        for (line in File(root, "$module/module.yaml").readLines()) {
            if (line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("#")) {
                section = line.substringBefore(':').trim()
                continue
            }
            val entry = line.trim()
            val name = section ?: continue
            if (!(name.startsWith("dependencies") || name.startsWith("test-dependencies")) || !entry.startsWith("- ../")) continue
            val path = entry.removePrefix("- ").substringBefore(':').trim()
            val target = File(root, module).resolve(path).canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
            edges += Edge(module, target, test = name.startsWith("test-dependencies"))
        }
        return edges
    }

    private fun actual(): List<String> = modules().flatMap(::edges).map { it.toString() }.distinct().sorted()

    @Test fun edgesStayWithinAllowlist() {
        val modules = modules().toSet()
        val edges = modules().flatMap(::edges)
        val unknown = edges.filter { it.to !in modules }
        assertTrue(unknown.isEmpty(), "Dependencies on modules missing from project.yaml:\n${unknown.joinToString("\n")}")

        val actual = actual()
        if (System.getenv("UPDATE_MODULE_EDGES") == "1") {
            allowlist.writeText(actual.joinToString("\n", postfix = "\n"))
            return
        }
        val allowed = allowlist.readLines().filter { it.isNotBlank() }.toSet()
        val added = actual.filterNot { it in allowed }
        if (added.isNotEmpty()) fail(
            """
            New module dependency edges are not in the allowlist (${allowlist.relativeTo(root)}):
            ${added.joinToString("\n") { "  $it" }}

            Module layering is governed by docs/adr/0029-module-layering-and-standalone-harness.md. If the edge is
            intended, add the lines above to the allowlist, sorted, one `from -> to` per line (`[test]` marks
            test-dependencies), or regenerate it with UPDATE_MODULE_EDGES=1 and review the diff. Otherwise
            remove the dependency.
            """.trimIndent(),
        )
    }
}
