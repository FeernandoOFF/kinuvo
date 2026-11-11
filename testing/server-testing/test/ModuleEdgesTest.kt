package kinuvo.testing.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Enforces the module layering of docs/adr/0029-module-layering-and-standalone-harness.md. */
class ModuleEdgesTest {
    private data class Edge(val from: String, val to: String, val test: Boolean) {
        override fun toString() = "$from -> $to" + if (test) " [test]" else ""
    }

    /** A module matching [from] may depend on modules matching [to]; test-dependencies may also reach [testTo]. A pattern ending in a slash and star matches every module under that directory. */
    private class Rule(val from: String, val to: List<String>, val testTo: List<String> = emptyList())

    private val hostTo = listOf("server/*", "harness/*", "providers/*", "plugins/*", "gateways/*", "models", "protocol")
    private val harnessTests = listOf("testing/*")
    private val serverTests = listOf("testing/*", "client/*", "protocol")

    private val rules = listOf(
        Rule("protocol", emptyList(), harnessTests),
        Rule("models", listOf("protocol"), harnessTests),
        Rule("client/*", listOf("protocol", "client/*"), harnessTests),
        Rule("harness/api", listOf("protocol"), harnessTests),
        Rule("harness/builtin", listOf("harness/api", "protocol"), harnessTests),
        Rule("harness/acp", listOf("harness/api", "protocol"), harnessTests),
        Rule("harness/remote", listOf("harness/api", "protocol", "client/sdk"), serverTests),
        Rule("server/plugin-api", listOf("harness/api", "protocol"), serverTests),
        Rule("server/core", listOf("server/plugin-api", "harness/api", "protocol"), serverTests + "server/rpc"),
        Rule("server/rpc", listOf("server/core", "server/plugin-api", "harness/api", "protocol"), serverTests),
        Rule("providers/*", listOf("harness/builtin", "harness/api", "server/plugin-api", "models", "protocol"), serverTests),
        Rule("plugins/*", listOf("server/plugin-api", "models", "protocol"), serverTests),
        Rule("gateways/*", listOf("client/sdk", "harness/api", "protocol"), serverTests),
        Rule("server/jvm", hostTo, serverTests),
        Rule("server/embedded", hostTo, serverTests),
        Rule("apps/*", listOf("*")),
        Rule("testing/*", listOf("*")),
    )

    /** Edges that break a rule today; each needs its reason, and the test fails once one is no longer needed. */
    private val exceptions = emptyMap<String, String>()

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "project.yaml").isFile }

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

    private fun matches(pattern: String, module: String) = when {
        pattern == "*" -> true
        pattern.endsWith("/*") -> module.startsWith(pattern.removeSuffix("*"))
        else -> module == pattern
    }

    private fun allEdges() = modules().flatMap(::edges).distinct()

    private fun allowed(edge: Edge): Boolean {
        val rule = rules.firstOrNull { matches(it.from, edge.from) } ?: return false
        return (rule.to + if (edge.test) rule.testTo else emptyList()).any { matches(it, edge.to) } || "${edge.from} -> ${edge.to}" in exceptions
    }

    private fun closure(start: String): Set<String> {
        val seen = mutableSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) edges(queue.removeFirst()).filter { !it.test }.forEach { if (seen.add(it.to)) queue += it.to }
        return seen - start
    }

    @Test fun everyModuleHasALayerRule() {
        val missing = modules().filter { module -> rules.none { matches(it.from, module) } }
        assertTrue(missing.isEmpty(), "Modules without a layer rule in ModuleEdgesTest:\n${missing.joinToString("\n")}")
    }

    @Test fun dependenciesStayWithinTheTargetLayering() {
        val modules = modules().toSet()
        val edges = allEdges()
        val unknown = edges.filter { it.to !in modules }
        assertTrue(unknown.isEmpty(), "Dependencies on modules missing from project.yaml:\n${unknown.joinToString("\n")}")

        val violations = edges.filterNot(::allowed)
        assertTrue(
            violations.isEmpty(),
            """
            Module dependency edges outside the layering of docs/adr/0029-module-layering-and-standalone-harness.md:
            ${violations.joinToString("\n") { "  $it" }}

            Remove the dependency, or change the layering: update the rules in ModuleEdgesTest and the ADR together.
            """.trimIndent(),
        )
    }

    @Test fun exceptionsAreStillNeeded() {
        val actual = allEdges().map { it.toString() }.toSet()
        val stale = exceptions.keys.filter { it !in actual }
        assertTrue(stale.isEmpty(), "Layering exceptions no longer needed; remove them:\n${stale.joinToString("\n")}")
    }

    @Test fun builtInHarnessIsStandalone() {
        for (module in listOf("harness/api", "harness/builtin")) {
            val reached = closure(module).filter { it != "protocol" && !it.startsWith("harness/") }
            assertEquals(emptyList(), reached, "$module reaches modules outside harness/ and protocol")
        }
    }

    @Test fun clientsReachNoServerCode() {
        val reached = modules().filter { it.startsWith("client/") }.flatMap { module -> closure(module).map { "$module -> $it" } }
            .filter { !it.substringAfter(" -> ").let { to -> to == "protocol" || to.startsWith("client/") } }
        assertEquals(emptyList(), reached, "client modules reach Server, harness, plugin or provider modules")
    }
}
