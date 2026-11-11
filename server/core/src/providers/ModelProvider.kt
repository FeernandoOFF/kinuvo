package kinuvo.server.providers

import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.ProviderAdmin
import kinuvo.server.plugin.ProviderPlugin

import kinuvo.harness.ModelPolicy

/** A model provider as a Server runs it: the [ModelPolicy] of the source a host registers with the built-in harness, paired by ID with its [ProviderAdmin]. */
interface ModelProvider : ModelPolicy, ProviderAdmin {
    override val id: String
}

private class PairedModelProvider(source: ModelPolicy, admin: ProviderAdmin) : ModelProvider, ModelPolicy by source, ProviderAdmin by admin {
    override val id = source.id
    init { require(source.id == admin.id) { "Model source '${source.id}' and provider admin '${admin.id}' differ" } }
}

/**
 * What a host registers for model providers: their admin halves as plugins, and their sources of type [S], which the host also registers with the built-in harness.
 * [sources] is read each time, so it can follow providers that change, e.g. custom endpoints.
 */
class ModelProviders<out S : ModelPolicy>(val plugins: List<ProviderPlugin> = emptyList(), val sources: () -> List<S> = { emptyList() }) {
    companion object {
        fun <T> of(provider: T) where T : ModelPolicy, T : ProviderPlugin = of(listOf(provider))
        fun <T> of(providers: List<T>) where T : ModelPolicy, T : ProviderPlugin = ModelProviders(providers) { providers }
    }
}

operator fun <S : ModelPolicy> ModelProviders<S>.plus(other: ModelProviders<S>) = ModelProviders(plugins + other.plugins) { sources() + other.sources() }

/** Pairs each admin of [plugins] with the source of its ID; an admin without one can't run models, so it fails. */
internal fun pairModelProviders(plugins: List<HarnessPlugin>, sources: List<ModelPolicy>): List<ModelProvider> {
    val byId = sources.associateBy { it.id }
    return plugins.filterIsInstance<ProviderPlugin>().flatMap { it.providerAdmins }.map { admin ->
        PairedModelProvider(byId[admin.id] ?: error("No model source registered for provider '${admin.id}'"), admin)
    }
}
