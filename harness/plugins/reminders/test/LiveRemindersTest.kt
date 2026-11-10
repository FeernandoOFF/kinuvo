package kinuvo.harness.reminders

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveRemindersTest {
    @Test
    fun listsReminders() = runTest {
        if (System.getenv("KINUVO_LIVE_REMINDERS") != "1") return@runTest
        val home = Files.createTempDirectory("kinuvo-live-reminders-").toFile()
        try {
            val provider = RemindersCli()
            val lists = provider.lists()
            assertTrue(lists.all { it.id.isNotBlank() && it.name.isNotBlank() })
        } finally {
            home.deleteRecursively()
        }
    }
}
