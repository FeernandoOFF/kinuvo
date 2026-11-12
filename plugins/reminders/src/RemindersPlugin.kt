package kinuvo.plugins.reminders

import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.TaskProvider
import kinuvo.protocol.Platform
import kinuvo.protocol.currentPlatform
import kinuvo.protocol.PluginDefinition

class RemindersPlugin(private val platform: Platform = currentPlatform) : HarnessPlugin {
    override val definition = PluginDefinition("kinuvo.reminders", "Reminders")
    override val taskProviders: List<TaskProvider> by lazy {
        if (platform == Platform.MacOS) listOf(TaskProvider.of("reminders", "Apple Reminders", RemindersCli())) else emptyList()
    }
}
