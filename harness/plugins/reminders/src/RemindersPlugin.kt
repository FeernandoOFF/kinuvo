package kinuvo.harness.reminders

import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.tasks.TaskProvider
import kinuvo.protocol.Platform
import kinuvo.protocol.currentPlatform

class RemindersPlugin(private val platform: Platform = currentPlatform) : HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.reminders", "Reminders")
    override val taskProviders: List<TaskProvider> by lazy {
        if (platform == Platform.MacOS) listOf(RemindersCli()) else emptyList()
    }
}
