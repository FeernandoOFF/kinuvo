package kinuvo.plugins.apple

import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.Risk
import kinuvo.protocol.capability.Integration
import kinuvo.protocol.device.Platform
import kinuvo.server.plugin.CoreToolRegistry
import kinuvo.server.plugin.RunContext
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.checkPluginInstances
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApplePluginTest {
    private val system = FakeAppleSystem()
    private val plugin = ApplePlugin(system, Platform.MacOS)

    private suspend fun tools(vararg enabled: String): Map<String, Tool> =
        plugin.prepare(RunContext(Agent(AgentId("main"), "Main", AgentConfig(tools = enabled.toList())), null), false).tools.associateBy { it.descriptor.name }

    private suspend fun Map<String, Tool>.run(name: String, args: JsonObject = JsonObject(emptyMap())) = getValue(name).execute(args)

    @Test
    fun onlyAppleServersOfferIt() {
        assertEquals(listOf("reminders"), plugin.taskProviders.map { it.id })
        assertEquals(setOf("reminders", "calendar"), plugin.integrations.getValue(Integration.Tools))
        assertEquals(listOf("reminders"), ApplePlugin(system, Platform.IOS).taskProviders.map { it.id })
        Platform.entries.filter { it != Platform.MacOS && it != Platform.IOS }.forEach {
            assertTrue(ApplePlugin(system, it).taskProviders.isEmpty() && ApplePlugin(system, it).integrations.isEmpty(), it.displayName)
        }
    }

    @Test
    fun coexistsWithTheAppleModelProvider() {
        val other = object : kinuvo.server.plugin.HarnessPlugin { override val definition = kinuvo.protocol.plugin.PluginDefinition("kinuvo.apple", "Apple") }
        listOf(other, plugin).checkPluginInstances()
    }

    @Test
    fun eachFrameworkIsTurnedOnByItsOwnGroup() = runTest {
        assertTrue(tools().isEmpty())
        assertTrue(tools("reminders").keys.all { it.startsWith("reminders_") })
        assertTrue(tools("calendar").keys.all { it.startsWith("calendar_") })
        assertEquals(10, tools("reminders", "calendar").size)
        assertTrue(ApplePlugin(system, Platform.Linux).prepare(RunContext(Agent(AgentId("main"), "Main", AgentConfig(tools = listOf("reminders"))), null), false).tools.isEmpty())
    }

    @Test
    fun readsAreLowAndChangesNeedApproval() = runTest {
        val all = tools("reminders", "calendar")
        all.values.forEach { assertEquals(CoreToolRegistry[it.descriptor.name]!!.risk, it.risk, it.descriptor.name) }
        assertEquals(setOf("reminders_lists", "reminders_list", "reminders_get", "calendar_list", "calendar_events"), all.filterValues { it.risk == Risk.Low }.keys)
        assertEquals(setOf("calendar_event_delete"), all.filterValues { it.risk == Risk.High }.keys)
    }

    @Test
    fun remindersTools() = runTest {
        val tools = tools("reminders")
        assertEquals("- errands \"Errands\"\n- work \"Work\"", tools.run("reminders_lists"))
        assertEquals("- r1 \"Buy milk\" [open] in Errands, due 2026-11-12\n- r2 \"Call mum\" [open] in Errands, due 2026-11-13T09:30\n  About the trip",
            tools.run("reminders_list", buildJsonObject { put("list", "") }))
        assertEquals(listOf("reminders.list" to JsonObject(emptyMap())), system.calls.filter { it.first == "reminders.list" })
        assertTrue("[done]" in tools.run("reminders_list", buildJsonObject { put("include_done", true) }))
        assertTrue(tools.run("reminders_create", buildJsonObject { put("title", "Pay rent"); put("due", "2026-11-20T08:00") }).startsWith("Created.\n- r4 \"Pay rent\""))
        assertTrue("[done]" in tools.run("reminders_update", buildJsonObject { put("id", "r1"); put("done", true) }))
        assertTrue("due" !in tools.run("reminders_update", buildJsonObject { put("id", "r1"); put("due", "none") }))
        assertEquals("notFound", assertFailsWith<AppleException> { tools.run("reminders_get", buildJsonObject { put("id", "nope") }) }.code)
        system.access = "denied"
        assertEquals("denied", assertFailsWith<AppleException> { tools.run("reminders_lists") }.code)
    }

    @Test
    fun calendarTools() = runTest {
        val tools = tools("calendar")
        assertEquals("- home \"Home\"\n- holidays \"Holidays\" (read-only)", tools.run("calendar_list"))
        assertEquals("- e1 \"Dentist\" 2026-11-12T10:00 to 2026-11-12T11:00 at Main St in Home\n- e2 \"Holiday\" 2026-11-14 to 2026-11-16 (all day) in Home", tools.run("calendar_events"))
        assertEquals("No events.", tools.run("calendar_events", buildJsonObject { put("query", "zzz") }))
        assertTrue(tools.run("calendar_event_create", buildJsonObject { put("title", "Lunch"); put("start", "2026-11-13T12:30") }).startsWith("Created.\n- e3 \"Lunch\""))
        assertTrue(tools.run("calendar_event_update", buildJsonObject { put("id", "e1"); put("title", "Dentist (moved)") }).contains("Dentist (moved)"))
        assertEquals("Deleted.", tools.run("calendar_event_delete", buildJsonObject { put("id", "e1") }))
        assertEquals("notFound", assertFailsWith<AppleException> { tools.run("calendar_event_delete", buildJsonObject { put("id", "e1") }) }.code)
    }
}
