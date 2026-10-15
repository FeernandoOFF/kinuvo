package kinuvo.harness.reminders

import kinuvo.protocol.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemindersPluginTest {
    @Test
    fun onlyMacServersOfferReminders() {
        assertEquals(listOf("reminders"), RemindersPlugin(Platform.MacOS).taskProviders.map { it.id })
        Platform.entries.filter { it != Platform.MacOS }.forEach {
            assertTrue(RemindersPlugin(it).taskProviders.isEmpty(), it.displayName)
        }
    }
}
