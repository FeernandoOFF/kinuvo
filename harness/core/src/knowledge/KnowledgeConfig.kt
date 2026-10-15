package kinuvo.harness.core.knowledge

import kotlinx.serialization.Serializable

/**
 * `knowledge.yaml`: the [provider] that keeps Knowledge (a [KnowledgeSource.id]), the folder for the `markdown` one
 * ([path]), settings for other providers ([options], e.g. Anytype's `url`, `apiKey` and `space`), and whether Agents
 * reflect after conversations. Each provider's conventions are in `conventions/`.
 */
@Serializable
data class KnowledgeConfig(
    val path: String? = null,
    val reflection: Boolean = true,
    val provider: String = MARKDOWN,
    val options: Map<String, String> = emptyMap(),
    val reflectionPrompt: String? = null,
) {
    companion object {
        const val MARKDOWN = "markdown"
    }
}
