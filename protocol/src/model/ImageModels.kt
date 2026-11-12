package kinuvo.protocol.model

/** Best available image output first; ties are stable across catalog refreshes. */
fun ModelsCatalog.imageModels(): List<ProviderModelRef> = providers.flatMap { provider ->
    provider.models.filter { "image" in it.output.orEmpty() && it.state.actionable }
        .map { ProviderModelRef(provider.id, it.id) }
}.sortedWith(compareBy<ProviderModelRef> { imageRank(it.model) }.thenByDescending { it.model }.thenBy { it.provider })

private fun imageRank(model: String): Int = when {
    model.startsWith("gpt-image-") && "mini" !in model -> 0
    model.startsWith("gpt-image-") -> 1
    model.contains("image", ignoreCase = true) && !model.startsWith("dall-e") -> 2
    model == "dall-e-3" -> 4
    model.startsWith("dall-e") -> 5
    else -> 3
}

fun resolveImageModel(chat: ProviderModelRef?, configured: ProviderModelRef?, available: List<ProviderModelRef>): ProviderModelRef? =
    chat?.takeIf { it in available } ?: configured?.takeIf { it in available } ?: available.firstOrNull()
