package kinuvo.server

import kinuvo.server.plugin.CUSTOM_PROVIDERS
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.Store
import kinuvo.server.plugin.readTextOrNull
import kinuvo.server.plugin.writeAtomically
import kinuvo.protocol.SessionEntry
import kotlinx.io.files.Path
import kinuvo.server.providers.ModelProvider

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
