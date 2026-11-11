package kinuvo.harness.core.plugin

import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.ProviderAdmin
import kinuvo.server.plugin.ProviderPlugin

/** A model provider as a Server runs it: the [ModelSource] a host registers with the built-in harness, paired by ID with its [ProviderAdmin]. */
interface ModelProvider : ModelSource, ProviderAdmin {
    override val id: String
}

private class PairedModelProvider(source: ModelSource, admin: ProviderAdmin) : ModelProvider, ModelSource by source, ProviderAdmin by admin {
    override val id = source.id
    init { require(source.id == admin.id) { "Model source '${source.id}' and provider admin '${admin.id}' differ" } }
}

/**
 * What a host registers for model providers: their admin halves as plugins, and the sources it registers with the built-in harness.
 * [sources] is read each time, so it can follow providers that change, e.g. custom endpoints.
 */
class ModelProviders(val plugins: List<ProviderPlugin> = emptyList(), val sources: () -> List<ModelSource> = { emptyList() }) {
    operator fun plus(other: ModelProviders) = ModelProviders(plugins + other.plugins) { sources() + other.sources() }

    companion object {
        fun <T> of(provider: T) where T : ModelSource, T : ProviderPlugin = of(listOf(provider))
        fun <T> of(providers: List<T>) where T : ModelSource, T : ProviderPlugin = ModelProviders(providers) { providers }
    }
}

/** Pairs each admin of [plugins] with the source of its ID; an admin without one can't run models, so it fails. */
internal fun pairModelProviders(plugins: List<HarnessPlugin>, sources: List<ModelSource>): List<ModelProvider> {
    val byId = sources.associateBy { it.id }
    return plugins.filterIsInstance<ProviderPlugin>().flatMap { it.providerAdmins }.map { admin ->
        PairedModelProvider(byId[admin.id] ?: error("No model source registered for provider '${admin.id}'"), admin)
    }
}
