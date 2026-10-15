package kinuvo.harness.reminders

import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.tasks.TaskProvider

class RemindersPlugin : HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.reminders", "Reminders")
    override val taskProviders: List<TaskProvider> by lazy { listOf(RemindersCli()) }
}
