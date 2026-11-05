package kinuvo.harness.server

import kinuvo.harness.core.plugin.CUSTOM_PROVIDERS
import kinuvo.harness.core.plugin.ModelProvider
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.Store
import kinuvo.harness.core.store.readTextOrNull
import kinuvo.harness.core.store.writeAtomically
import kinuvo.protocol.SessionEntry
import kotlinx.io.files.Path

/**
 * A custom provider with a built-in provider's ID used to replace that provider, so Agents and Sessions naming the bare ID
 * meant the custom one. Once, they're pointed at `custom/<id>` so they keep reaching the same endpoint.
 */
internal suspend fun claimShadowedProviders(files: AgentFiles, store: Store, providers: List<ModelProvider>) {
    val marker = Path(files.home, "migrations", "custom-providers")
    if (marker.readTextOrNull() != null) return
    val ids = providers.map { it.id }.toSet()
    val shadowed = ids.filter { it.startsWith(CUSTOM_PROVIDERS) && it.removePrefix(CUSTOM_PROVIDERS) in ids }.associateBy { it.removePrefix(CUSTOM_PROVIDERS) }
    store.agents().forEach { agent ->
        shadowed[agent.config.provider]?.let { store.saveConfig(agent, agent.config.copy(provider = it)) }
        store.sessions(agent.id).forEach { session ->
            shadowed[session.settings.provider]?.let { store.append(agent.id, session.id, SessionEntry.Settings(session.settings.copy(provider = it))) }
        }
    }
    writeAtomically(marker, "done")
}
