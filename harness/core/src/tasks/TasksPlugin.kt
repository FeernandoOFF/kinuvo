package kinuvo.harness.core.tasks

import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.PluginTools
import kinuvo.server.plugin.RunContext
import kinuvo.protocol.ToolNames
import kinuvo.protocol.PluginDefinition

/** Task runs can manage their assigned Task even without general access to the Board. */
class TasksPlugin(private val tasks: Tasks) : HarnessPlugin {
    override val definition = PluginDefinition("kinuvo.tasks", "Tasks")
    override suspend fun prepare(run: RunContext, activate: Boolean) =
        PluginTools(when {
            ToolNames.TASKS in run.agent.config.tools -> listOf(TasksTool(tasks, run.agent))
            run.taskId != null -> listOf(TasksTool(tasks, run.agent, run.taskId))
            else -> emptyList()
        })
}
