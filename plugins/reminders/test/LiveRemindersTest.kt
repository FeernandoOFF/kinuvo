package kinuvo.plugins.reminders

import kotlin.test.AfterTest
import kinuvo.testing.harness.TemporaryDirectories
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveRemindersTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest
    fun deleteTemporaryDirectories() = temporaryDirectories.close()

    @Test
    fun listsReminders() = runTest {
        if (System.getenv("KINUVO_LIVE_REMINDERS") != "1") return@runTest
        val home = temporaryDirectories.create("kinuvo-live-reminders-").toFile()
        try {
            val provider = RemindersCli()
            val lists = provider.lists()
            assertTrue(lists.all { it.id.isNotBlank() && it.name.isNotBlank() })
        } finally {
            home.deleteRecursively()
        }
    }
}
