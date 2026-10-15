package kinuvo.harness.core.tasks

import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.PluginTools
import kinuvo.harness.core.RunContext
import kinuvo.harness.core.tools.ToolNames

/** Task runs can manage their assigned Task even without general access to the Board. */
class TasksPlugin(private val tasks: Tasks) : HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.tasks", "Tasks")
    override suspend fun prepare(run: RunContext, activate: Boolean) =
        PluginTools(when {
            ToolNames.TASKS in run.agent.config.tools -> listOf(TasksTool(tasks, run.agent))
            run.taskId != null -> listOf(TasksTool(tasks, run.agent, run.taskId))
            else -> emptyList()
        })
}
