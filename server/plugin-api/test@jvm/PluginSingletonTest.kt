package kinuvo.server.plugin

import kinuvo.protocol.plugin.PluginDefinition
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginSingletonTest {
    private fun plugin(id: String, check: ExecCheck? = null) = object : ServerPlugin {
        override val definition = PluginDefinition(id, id)
        override val execCheck = check
    }

    private val check = ExecCheck { _, _ -> error("Not used") }

    @Test fun aSecondPluginOfASingletonCapabilityFailsNamingBoth() {
        val error = assertFailsWith<IllegalArgumentException> { listOf(plugin("a", check), plugin("b"), plugin("c", check)).checkPluginInstances() }
        val message = error.message.orEmpty()
        assertTrue("execCheck" in message && "a/default" in message && "c/default" in message && "b/default" !in message, message)
    }

    @Test fun oneOfEachSingletonCapabilityPasses() {
        listOf(plugin("a", check), plugin("b")).checkPluginInstances()
    }
}
