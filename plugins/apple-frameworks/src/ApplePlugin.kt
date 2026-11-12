package kinuvo.plugins.apple

import kinuvo.protocol.capability.Integration
import kinuvo.protocol.device.Platform
import kinuvo.protocol.device.currentPlatform
import kinuvo.protocol.plugin.PluginDefinition
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.PluginTools
import kinuvo.server.plugin.RunContext
import kinuvo.server.plugin.TaskProvider

/**
 * Apple's frameworks on a Mac or iPhone Server: Reminders as the `reminders` Task provider, and Reminders and Calendar
 * tools an Agent turns on per framework under its `tools`. Each framework is a section of [Apple]'s boundary, so another
 * (Contacts, Location) is a new section.
 */
class ApplePlugin(system: AppleSystem, platform: Platform = currentPlatform) : HarnessPlugin {
    /** For clients to read each framework's permission and ask for it. */
    val apple = Apple(system)
    // The Apple model provider already is `kinuvo.apple/default`.
    override val definition = PluginDefinition("kinuvo.apple", "Apple")
    override val instanceId = "frameworks"
    private val offered = platform == Platform.MacOS || platform == Platform.IOS

    override val integrations = if (offered) mapOf(Integration.Tools to setOf(ToolNames.REMINDERS, ToolNames.CALENDAR)) else emptyMap()
    override val taskProviders = if (offered) listOf(TaskProvider.of("reminders", "Apple Reminders", AppleReminders(apple))) else emptyList()

    override suspend fun prepare(run: RunContext, activate: Boolean): PluginTools {
        val tools = run.agent.config.tools
        return PluginTools(
            (if (offered && ToolNames.REMINDERS in tools) reminderTools(apple) else emptyList()) +
                (if (offered && ToolNames.CALENDAR in tools) calendarTools(apple) else emptyList()),
        )
    }
}
