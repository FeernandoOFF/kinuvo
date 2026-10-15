package kinuvo.app.agent

import kinuvo.protocol.*

fun ModelsCatalog.model(provider: String, id: String) = providers.firstOrNull { it.id == provider }?.models?.firstOrNull { it.id == id }

fun ModelInfo.reasoningFor(current: String): String {
    val levels = reasoning
    val default = defaultReasoning
    return when {
        levels?.isEmpty() == true -> "none"
        current.isNotEmpty() && (levels == null || current in levels) -> current
        default != null && (levels == null || default in levels) -> default
        else -> levels?.firstOrNull() ?: current
    }
}

fun SessionSettings.select(provider: String, model: ModelInfo, defaults: AgentSettings) = copy(
    provider = provider,
    model = model.id,
    reasoning = model.reasoningFor(reasoning ?: defaults.reasoning),
)
